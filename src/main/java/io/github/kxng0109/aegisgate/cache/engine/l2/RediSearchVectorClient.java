package io.github.kxng0109.aegisgate.cache.engine.l2;

import io.github.kxng0109.aegisgate.proxy.embeddings.VectorEncodingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Low-level client for RediSearch / Redis Vector Similarity Search (VSS) module commands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RediSearchVectorClient {

	private static final byte[] FT_CREATE = "FT.CREATE".getBytes(StandardCharsets.UTF_8);
	private static final byte[] FT_SEARCH = "FT.SEARCH".getBytes(StandardCharsets.UTF_8);
	private static final byte[] FT_DROPINDEX = "FT.DROPINDEX".getBytes(StandardCharsets.UTF_8);

	private final RedisConnectionFactory redisConnectionFactory;

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
			String msg = ex.getMessage() != null ? ex.getMessage() : "";
			if (msg.contains("Index already exists") || msg.contains("BUSYKEY") || msg.contains("already exists")) {
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
		String queryStr = "(" + filterQuery + ")=>[KNN " + k + " @embedding $vec_blob AS score]";

		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			byte[][] args = new byte[][]{
					indexName.getBytes(StandardCharsets.UTF_8),
					queryStr.getBytes(StandardCharsets.UTF_8),
					"PARAMS".getBytes(StandardCharsets.UTF_8),
					"2".getBytes(StandardCharsets.UTF_8),
					"vec_blob".getBytes(StandardCharsets.UTF_8),
					vectorBytes,
					"SORTBY".getBytes(StandardCharsets.UTF_8),
					"score".getBytes(StandardCharsets.UTF_8),
					"ASC".getBytes(StandardCharsets.UTF_8),
					"LIMIT".getBytes(StandardCharsets.UTF_8),
					"0".getBytes(StandardCharsets.UTF_8),
					String.valueOf(k).getBytes(StandardCharsets.UTF_8),
					"DIALECT".getBytes(StandardCharsets.UTF_8),
					"2".getBytes(StandardCharsets.UTF_8)
			};

			Object rawResult = connection.execute("FT.SEARCH", args);
			return parseSearchResults(rawResult);
		} catch (Exception ex) {
			log.warn("RediSearch KNN search failed on index '{}': {}", indexName, ex.getMessage());
			return Collections.emptyList();
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
			connection.hMSet(rawKey, fields);
			if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
				connection.expire(rawKey, ttl.toSeconds());
			}
		} catch (DataAccessException ex) {
			log.warn("Failed to save vector document '{}': {}", docKey, ex.getMessage());
		}
	}

	/**
	 * Deletes a cached document key.
	 *
	 * @param docKey document key
	 */
	public void deleteDocument(String docKey) {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			connection.del(docKey.getBytes(StandardCharsets.UTF_8));
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

	@SuppressWarnings("unchecked")
	private List<VectorSearchResult> parseSearchResults(Object rawResult) {
		if (!(rawResult instanceof List<?> list) || list.isEmpty()) {
			return Collections.emptyList();
		}

		Object countObj = list.getFirst();
		long totalCount = (countObj instanceof Number num) ? num.longValue() : 0L;
		if (totalCount <= 0) {
			return Collections.emptyList();
		}

		List<VectorSearchResult> results = new ArrayList<>();
		// Results format: [total_count, doc_key_1, [attr1, val1, attr2, val2, ...], doc_key_2, ...]
		for (int i = 1; i < list.size(); i += 2) {
			if (i + 1 >= list.size()) {
				break;
			}
			String docKey = toUtf8String(list.get(i));
			Object attrsObj = list.get(i + 1);

			Map<String, String> fieldMap = new HashMap<>();
			double distance = 1.0;

			if (attrsObj instanceof List<?> attrList) {
				for (int j = 0; j < attrList.size(); j += 2) {
					if (j + 1 >= attrList.size()) {
						break;
					}
					String attrName = toUtf8String(attrList.get(j));
					String attrVal = toUtf8String(attrList.get(j + 1));
					fieldMap.put(attrName, attrVal);

					if ("score".equalsIgnoreCase(attrName) || "dist".equalsIgnoreCase(attrName)) {
						try {
							distance = Double.parseDouble(attrVal);
						} catch (NumberFormatException ignored) {
						}
					}
				}
			}

			results.add(new VectorSearchResult(docKey, distance, fieldMap));
		}

		return results;
	}

	private String toUtf8String(Object obj) {
		if (obj == null) {
			return "";
		}
		if (obj instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return obj.toString();
	}
}
