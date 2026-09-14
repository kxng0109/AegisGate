package io.github.kxng0109.aegisgate.cache.engine.l2;

import io.github.kxng0109.aegisgate.proxy.embeddings.VectorEncodingUtils;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level client for RediSearch / Redis Vector Similarity Search (VSS) module commands.
 */
@Slf4j
@Component
public class RediSearchVectorClient {

	/** Server-side query bound in ms (server default is 500): slow queries return, never cascade. */
	private static final long SEARCH_TIMEOUT_MILLIS = 2000;

	/** Retry-budget cap: refill one token per ten successes, so retries stay near a 10% ratio. */
	private static final long MAX_RETRY_TOKENS = 1000;

	/** Warn every Nth consecutive failure after the initial burst so logs never flood. */
	private static final long WARN_EVERY_FAILURES = 100;

	private static final byte[] FT_CREATE = "FT.CREATE".getBytes(StandardCharsets.UTF_8);
	private static final byte[] FT_SEARCH = "FT.SEARCH".getBytes(StandardCharsets.UTF_8);
	private static final byte[] FT_DROPINDEX = "FT.DROPINDEX".getBytes(StandardCharsets.UTF_8);

	@Qualifier("vectorRedisConnectionFactory")
	private final RedisConnectionFactory redisConnectionFactory;

	private final MeterRegistry meterRegistry;

	private final AtomicLong failureCount = new AtomicLong();

	private final AtomicLong successCount = new AtomicLong();

	private final AtomicLong retryTokens = new AtomicLong(50);

	public RediSearchVectorClient(
			@Qualifier("vectorRedisConnectionFactory") RedisConnectionFactory redisConnectionFactory,
			@Nullable MeterRegistry meterRegistry) {
		this.redisConnectionFactory = redisConnectionFactory;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Escapes special punctuation characters reserved by the RediSearch query parser.
	 *
	 * @param tag raw tag string
	 * @return escaped tag safe for inclusion in {@code @field:{tag}}
	 */
	public static String escapeTag(String tag) {
		if (tag == null || tag.isEmpty()) {
			return "";
		}
		return tag.replaceAll("([,.<>\\{\\}\\[\\]\"':;!@#$%^&*()\\-+=\\~|/])", "\\\\$1");
	}

	/**
	 * Creates an HNSW vector index in Redis if it does not already exist.
	 *
	 * @param indexName  index identifier (e.g. {@code aegis:cache:idx})
	 * @param prefix     key prefix to index (e.g. {@code aegis:cache:doc:})
	 * @param dimensions vector dimension count (e.g. 1536)
	 * @return true if created, false if already exists or unavailable
	 */
	public boolean createIndexIfNotExists(String indexName, String prefix, int dimensions) {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			byte[][] args = new byte[][]{
					indexName.getBytes(StandardCharsets.UTF_8),
					"ON".getBytes(StandardCharsets.UTF_8),
					"HASH".getBytes(StandardCharsets.UTF_8),
					"PREFIX".getBytes(StandardCharsets.UTF_8),
					"1".getBytes(StandardCharsets.UTF_8),
					prefix.getBytes(StandardCharsets.UTF_8),
					"SCHEMA".getBytes(StandardCharsets.UTF_8),
					"owner_id".getBytes(StandardCharsets.UTF_8),
					"TAG".getBytes(StandardCharsets.UTF_8),
					"model".getBytes(StandardCharsets.UTF_8),
					"TAG".getBytes(StandardCharsets.UTF_8),
					"prefix_hash".getBytes(StandardCharsets.UTF_8),
					"TAG".getBytes(StandardCharsets.UTF_8),
					"system_prompt_hash".getBytes(StandardCharsets.UTF_8),
					"TAG".getBytes(StandardCharsets.UTF_8),
					"embedding".getBytes(StandardCharsets.UTF_8),
					"VECTOR".getBytes(StandardCharsets.UTF_8),
					"HNSW".getBytes(StandardCharsets.UTF_8),
					"6".getBytes(StandardCharsets.UTF_8),
					"TYPE".getBytes(StandardCharsets.UTF_8),
					"FLOAT32".getBytes(StandardCharsets.UTF_8),
					"DIM".getBytes(StandardCharsets.UTF_8),
					String.valueOf(dimensions).getBytes(StandardCharsets.UTF_8),
					"DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
					"COSINE".getBytes(StandardCharsets.UTF_8)
			};
			connection.execute("FT.CREATE", args);
			log.info("Created RediSearch vector index '{}' for prefix '{}' (dim={})", indexName, prefix, dimensions);
			return true;
		} catch (Exception ex) {
			String msg = (ex.getMessage() != null ? ex.getMessage() : "")
					+ (
					ex.getCause() != null && ex.getCause().getMessage() != null ?
							" " + ex.getCause().getMessage() : "");
			if (msg.contains("Index already exists") || msg.contains("BUSYKEY")
					|| msg.toLowerCase(Locale.ROOT).contains("already exists")) {
				log.debug("RediSearch index '{}' already exists", indexName);
				return false;
			}
			log.warn("RediSearch module not detected or index creation failed for '{}': {}", indexName, msg);
			return false;
		}
	}

	/**
	 * Executes a KNN vector search against the specified RediSearch index with tag filtering.
	 *
	 * @param indexName   target index name
	 * @param filterQuery tag and attribute filter query (e.g. {@code @owner_id:{tenant} @model:{gpt_4o}})
	 * @param queryVector dense float query vector
	 * @param k           maximum nearest neighbors to return
	 * @return list of search matches ordered by distance ascending
	 */
	public List<VectorSearchResult> searchKnn(String indexName, String filterQuery, float[] queryVector, int k) {
		byte[] vectorBytes = VectorEncodingUtils.floatsToLittleEndianBytes(queryVector);
		// No AS-alias and no SORTBY: KNN rows arrive best-first by construction, and aliasing the
		// score (AS score + SORTBY score) makes RediSearch return the literal score "nan" for every
		// row. WITHSCORES alone attaches the numeric distance positionally.
		String queryStr = "(" + filterQuery + ")=>[KNN " + k + " @embedding $vec_blob]";
		byte[][] args = new byte[][]{
				indexName.getBytes(StandardCharsets.UTF_8),
				queryStr.getBytes(StandardCharsets.UTF_8),
				"PARAMS".getBytes(StandardCharsets.UTF_8),
				"2".getBytes(StandardCharsets.UTF_8),
				"vec_blob".getBytes(StandardCharsets.UTF_8),
				vectorBytes,
				"LIMIT".getBytes(StandardCharsets.UTF_8),
				"0".getBytes(StandardCharsets.UTF_8),
				String.valueOf(k).getBytes(StandardCharsets.UTF_8),
				"TIMEOUT".getBytes(StandardCharsets.UTF_8),
				String.valueOf(SEARCH_TIMEOUT_MILLIS).getBytes(StandardCharsets.UTF_8),
				"DIALECT".getBytes(StandardCharsets.UTF_8),
				"2".getBytes(StandardCharsets.UTF_8),
				"WITHSCORES".getBytes(StandardCharsets.UTF_8)
		};

		try {
			return attemptSearch(args);
		} catch (Exception ex) {
			if (!isRetryableDesync(ex) || !takeRetryToken()) {
				return failClosed(indexName, ex);
			}
			recordRetry(causeClassName(ex));
			try {
				return attemptSearch(args);
			} catch (Exception retryEx) {
				return failClosed(indexName, retryEx);
			}
		}
	}

	private List<VectorSearchResult> attemptSearch(byte[][] args) {
		// NestedMultiOutput decodes integers, doubles and bulk strings without ever throwing on
		// unexpected element types, under both RESP2 arrays and RESP3 flattened maps (multiMap
		// delegates to multi). Score interpretation stays in mapResults below, where a non-numeric
		// score degrades its row instead of the reply.
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			Object rawResult = ((LettuceConnection) connection)
					.execute("FT.SEARCH", new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), args);
			refillRetryBudget();
			return parseRawResults(rawResult);
		}
	}

	/**
	 * Maps a raw {@code FT.SEARCH} reply defensively, accepting both wire shapes:
	 *
	 * <ul>
	 *   <li>RESP2 array: {@code [count, id, score, [fields], ...]} (WITHSCORES rows).</li>
	 *   <li>RESP3 map flattened by {@code NestedMultiOutput}: {@code [attributes, [...], results,
	 *       [[id, score, extra_attributes, [fields]], ...], total_results, N, ...]}.</li>
	 * </ul>
	 *
	 * <p>Rows whose score is missing or non-numeric (RediSearch emits the literal {@code nan} for undefined
	 * distances, which Lettuce's own parser chokes on) are skipped instead of poisoning the reply.</p>
	 */
	private List<VectorSearchResult> parseRawResults(Object rawResult) {
		if (!(rawResult instanceof List<?> list) || list.isEmpty()) {
			return Collections.emptyList();
		}
		if (list.getFirst() instanceof Number) {
			return parseResp2Rows(list);
		}
		return parseResp3Map(list);
	}

	private List<VectorSearchResult> parseResp2Rows(List<?> list) {
		List<VectorSearchResult> results = new ArrayList<>();
		// Element 0 is the total count; rows follow as id, score, fields triples (WITHSCORES).
		for (int i = 1; i + 2 < list.size(); i += 3) {
			String docKey = toUtf8String(list.get(i));
			Map<String, String> fieldMap = readFields(list.get(i + 2));
			double distance = resolveDistance(fieldMap, list.get(i + 1), docKey);
			if (Double.isNaN(distance)) {
				continue;
			}
			results.add(new VectorSearchResult(docKey, distance, fieldMap));
		}
		return results;
	}

	private List<VectorSearchResult> parseResp3Map(List<?> list) {
		for (int i = 0; i + 1 < list.size(); i += 2) {
			if ("results".equals(toUtf8String(list.get(i))) && list.get(i + 1) instanceof List<?> rows) {
				List<VectorSearchResult> results = new ArrayList<>();
				for (Object row : rows) {
					if (row instanceof List<?> cells && !cells.isEmpty()) {
						String docKey = toUtf8String(cells.get(0));
						Map<String, String> fieldMap = readResp3Fields(cells);
						Object positional = cells.size() > 1 ? cells.get(1) : null;
						double distance = resolveDistance(fieldMap, positional, docKey);
						if (Double.isNaN(distance)) {
							continue;
						}
						results.add(new VectorSearchResult(docKey, distance, fieldMap));
					}
				}
				return results;
			}
		}
		return Collections.emptyList();
	}

	private Map<String, String> readFields(Object fieldsObj) {
		Map<String, String> fieldMap = new HashMap<>();
		if (fieldsObj instanceof List<?> attrList) {
			for (int j = 0; j + 1 < attrList.size(); j += 2) {
				String name = toUtf8String(attrList.get(j));
				Object value = attrList.get(j + 1);
				fieldMap.put(name, value == null ? "" : toUtf8String(value));
			}
		}
		return fieldMap;
	}

	private Map<String, String> readResp3Fields(List<?> cells) {
		for (int i = 0; i + 1 < cells.size(); i++) {
			if ("extra_attributes".equals(toUtf8String(cells.get(i)))) {
				return readFields(cells.get(i + 1));
			}
		}
		return new HashMap<>();
	}

	private double resolveDistance(Map<String, String> fieldMap, Object positionalScore, String docKey) {
		// RediSearch projects the KNN vector distance into the __embedding_score field; the positional
		// WITHSCORES value is the text-search score, which is nan for pure-vector queries. Prefer the
		// vector distance, falling back to the positional value for backward compatibility.
		String vectorScore = fieldMap.get("__embedding_score");
		if (vectorScore != null) {
			return parseScore(vectorScore, docKey);
		}
		return parseScore(positionalScore, docKey);
	}

	private double parseScore(Object scoreObj, String docKey) {
		if (scoreObj == null) {
			return Double.NaN;
		}
		try {
			double parsed = Double.parseDouble(toUtf8String(scoreObj));
			return Double.isNaN(parsed) ? Double.NaN : parsed;
		} catch (NumberFormatException ex) {
			try {
				Counter.builder("aegis.cache.vector.search.nan_scores")
				       .tag("key", docKey)
				       .register(meterRegistry)
				       .increment();
			} catch (Exception ignored) {
			}
			return Double.NaN;
		}
	}

	/**
	 * Retries only connection/desync transients: multiplex misrouting, timeouts, dropped connections. Deterministic
	 * server replies (OOM, WRONGTYPE, unknown index, syntax) fail identically on retry and are never retried.
	 */
	private static boolean isRetryableDesync(Throwable ex) {
		boolean sawTransient = false;
		for (Throwable current = ex; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message != null && (message.contains("OOM") || message.contains("WRONGTYPE")
					|| message.contains("no such index") || message.contains("syntax error")
					|| message.contains("NOSCRIPT"))) {
				return false;
			}
			if (current instanceof UnsupportedOperationException || current instanceof TimeoutException
					|| current instanceof RedisConnectionException) {
				sawTransient = true;
			}
		}
		return sawTransient;
	}

	private boolean takeRetryToken() {
		return retryTokens.getAndUpdate(tokens -> tokens > 0 ? tokens - 1 : tokens) > 0;
	}

	private void refillRetryBudget() {
		if (successCount.incrementAndGet() % 10 == 0) {
			retryTokens.updateAndGet(tokens -> Math.min(MAX_RETRY_TOKENS, tokens + 1));
		}
	}

	private static String causeClassName(Throwable ex) {
		Throwable cause = ex.getCause();
		return cause == null ? "none" : cause.getClass().getName();
	}

	private void recordFailure(String causeClass) {
		try {
			Counter.builder("aegis.cache.vector.search.failures")
			       .tag("cause", causeClass)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}

	private void recordRetry(String causeClass) {
		try {
			Counter.builder("aegis.cache.vector.search.retries")
			       .tag("cause", causeClass)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}

	private List<VectorSearchResult> failClosed(String indexName, Exception ex) {
		String causeClass = causeClassName(ex);
		recordFailure(causeClass);
		logFailure(indexName, ex, causeClass);
		return Collections.emptyList();
	}

	private void logFailure(String indexName, Exception ex, String causeClass) {
		long failures = failureCount.incrementAndGet();
		if (failures <= 3 || failures % WARN_EVERY_FAILURES == 0) {
			// Spring Data Redis translates unrecognized failures to RedisSystemException("Unknown redis
			// exception"); the real signal is the cause chain, so log the classes, not just the message.
			log.warn("RediSearch KNN search failed on index '{}' (failure #{}): class={} message={} causeClass={} causeMessage={}",
					indexName, failures, ex.getClass().getName(), ex.getMessage(),
					causeClass, ex.getCause() == null ? "none" : ex.getCause().getMessage());
		} else {
			log.debug("RediSearch KNN search failure detail on index '{}'", indexName, ex);
		}
	}

	/**
	 * Saves a vector cache document to Redis with a TTL.
	 *
	 * @param docKey document key (e.g. {@code aegis:cache:doc:tenant:id})
	 * @param fields map of field names to byte values
	 * @param ttl    time-to-live duration
	 */
	public void saveVectorDocument(String docKey, Map<byte[], byte[]> fields, Duration ttl) {
		byte[] rawKey = docKey.getBytes(StandardCharsets.UTF_8);
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			if (isZeroNormVector(fields)) {
				try {
					Counter.builder("aegis.cache.vector.store.zero_norm_rejected")
					       .register(meterRegistry)
					       .increment();
				} catch (Exception ignored) {
				}
				log.warn("Refusing to store zero-norm vector document '{}': cosine distance is undefined",
						docKey);
				return;
			}
			connection.hashCommands().hMSet(rawKey, fields);
			if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
				connection.keyCommands().expire(rawKey, ttl.toSeconds());
			}
		} catch (DataAccessException ex) {
			log.warn("Failed to save vector document '{}': {}", docKey, ex.getMessage());
		}
	}

	/**
	 * Zero-norm embeddings make every cosine distance undefined (division by zero surfaces as {@code nan}
	 * scores that poison KNN replies), so they are rejected at the door rather than indexed.
	 */
	private static boolean isZeroNormVector(Map<byte[], byte[]> fields) {
		for (Map.Entry<byte[], byte[]> entry : fields.entrySet()) {
			if ("embedding".equals(toUtf8String(entry.getKey()))) {
				byte[] blob = entry.getValue();
				if (blob == null || blob.length == 0 || blob.length % 4 != 0) {
					return true;
				}
				for (int i = 0; i < blob.length; i += 4) {
					int bits = (blob[i] & 0xFF) | ((blob[i + 1] & 0xFF) << 8)
							| ((blob[i + 2] & 0xFF) << 16) | ((blob[i + 3] & 0xFF) << 24);
					if (Float.intBitsToFloat(bits) != 0.0f) {
						return false;
					}
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Deletes a cached document key.
	 *
	 * @param docKey document key
	 */
	public void deleteDocument(String docKey) {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			connection.keyCommands().del(docKey.getBytes(StandardCharsets.UTF_8));
		} catch (DataAccessException ex) {
			log.warn("Failed to delete document '{}': {}", docKey, ex.getMessage());
		}
	}

	/**
	 * Drops an index.
	 *
	 * @param indexName  index name
	 * @param deleteDocs whether to delete indexed documents
	 */
	public void dropIndex(String indexName, boolean deleteDocs) {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			if (deleteDocs) {
				connection.execute(
						"FT.DROPINDEX",
						indexName.getBytes(StandardCharsets.UTF_8),
						"DD".getBytes(StandardCharsets.UTF_8)
				);
			} else {
				connection.execute("FT.DROPINDEX", indexName.getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception ex) {
			log.debug("Drop index non-fatal response for '{}': {}", indexName, ex.getMessage());
		}
	}

	/**
	 * Returns the dimension declared by an existing vector index, or {@code -1} when the index does not exist or has no
	 * vector field. Read from the {@code FT.INFO} attributes list.
	 *
	 * @param indexName index identifier
	 * @return the index dimension, or {@code -1} if unknown
	 */
	public int vectorDimensionOf(String indexName) {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			// NestedMultiOutput decodes integers, doubles and bulk strings, so the mixed FT.INFO array
			// survives under both RESP2 and RESP3 (maps flatten to key/value sequences via multiMap).
			// The default ByteArrayOutput cannot represent numeric elements and fails every call, which
			// previously made dimension detection always return -1 and disabled index reconciliation.
			// Both factories are Lettuce-only by construction, so the cast is safe.
			Object info = ((LettuceConnection) connection).execute("FT.INFO",
					new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),
					new byte[][]{indexName.getBytes(StandardCharsets.UTF_8)});
			return parseVectorDimension(info);
		} catch (Exception ex) {
			log.debug("Could not read vector dimension for '{}': {}", indexName, ex.getMessage());
			return -1;
		}
	}

	@SuppressWarnings("unchecked")
	private static int parseVectorDimension(Object info) {
		if (!(info instanceof List<?> list) || list.isEmpty()) {
			return -1;
		}
		// FT.INFO returns a flat list: [index_name, name, index_options, "", index_definition,
		// key_type, HASH, prefixes, [...], default_score, 1, attributes,
		// [identifier, owner_id, attribute, owner_id, type, TAG, ...,
		//  identifier, embedding, attribute, embedding, type, VECTOR, algorithm, HNSW,
		//  data_type, FLOAT32, dim, 1536, distance_metric, COSINE, ...]]
		// Under RESP3 the same content arrives as a flattened map (key/value pairs inline), so the
		// attribute section is located first and then scanned recursively for the VECTOR dim.
		int attributesIdx = -1;
		for (int i = 0; i < list.size(); i++) {
			if ("attributes".equals(toUtf8String(list.get(i)))) {
				attributesIdx = i + 1;
				break;
			}
		}
		if (attributesIdx < 0 || attributesIdx >= list.size()) {
			return -1;
		}
		return scanVectorDim(list.get(attributesIdx));
	}

	private static int scanVectorDim(Object node) {
		if (!(node instanceof List<?> list)) {
			return -1;
		}
		// Only VECTOR fields carry a dim; TAG/TEXT/NUMERIC sections never do, so the first
		// dim+Number pair inside the attribute section is the embedding dimension.
		for (int i = 0; i < list.size(); i++) {
			if ("dim".equals(toUtf8String(list.get(i))) && i + 1 < list.size()
					&& list.get(i + 1) instanceof Number num) {
				return num.intValue();
			}
			int nested = scanVectorDim(list.get(i));
			if (nested > 0) {
				return nested;
			}
		}
		return -1;
	}

	private static String toUtf8String(Object obj) {
		if (obj == null) {
			return "";
		}
		if (obj instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return obj.toString();
	}
}
