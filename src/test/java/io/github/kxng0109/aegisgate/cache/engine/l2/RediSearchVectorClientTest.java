package io.github.kxng0109.aegisgate.cache.engine.l2;

import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.output.NestedMultiOutput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RediSearchVectorClient")
class RediSearchVectorClientTest {

	private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
	private final LettuceConnection connection = mock(LettuceConnection.class);
	private final RedisHashCommands hashCommands = mock(RedisHashCommands.class);
	private final RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);

	private final RediSearchVectorClient client = new RediSearchVectorClient(factory, null);

	@BeforeEach
	void setUp() {
		when(factory.getConnection()).thenReturn(connection);
		when(connection.hashCommands()).thenReturn(hashCommands);
		when(connection.keyCommands()).thenReturn(keyCommands);
	}

	private static List<Object> searchReply(long count, List<Object>... rows) {
		List<Object> reply = new ArrayList<>();
		reply.add(count);
		for (List<Object> row : rows) {
			reply.addAll(row);
		}
		return reply;
	}

	private static List<Object> docRow(String key, String score, Map<String, String> fields) {
		List<Object> attrs = new ArrayList<>();
		fields.forEach((name, value) -> {
			attrs.add(name.getBytes(StandardCharsets.UTF_8));
			attrs.add(value.getBytes(StandardCharsets.UTF_8));
		});
		return List.of(
				key.getBytes(StandardCharsets.UTF_8),
				score.getBytes(StandardCharsets.UTF_8),
				attrs
		);
	}

	@Test
	@DisplayName("escapeTag escapes RediSearch reserved punctuation characters")
	void escapeTag() {
		assertThat(RediSearchVectorClient.escapeTag("normal_tag")).isEqualTo("normal_tag");
		assertThat(RediSearchVectorClient.escapeTag("org-123.abc:test")).isEqualTo("org\\-123\\.abc\\:test");
		assertThat(RediSearchVectorClient.escapeTag(null)).isEmpty();
		assertThat(RediSearchVectorClient.escapeTag("")).isEmpty();
	}

	@Test
	@DisplayName("createIndexIfNotExists dispatches FT.CREATE command and handles existing index gracefully")
	void createIndexIfNotExists() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);

		boolean created = client.createIndexIfNotExists("test:idx", "test:doc:", 1536);
		assertThat(created).isTrue();
		verify(connection).execute(eq("FT.CREATE"), any(byte[][].class));

		// When index already exists exception is thrown
		when(connection.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(new RuntimeException(
				"Index already exists"));
		boolean alreadyExists = client.createIndexIfNotExists("test:idx", "test:doc:", 1536);
		assertThat(alreadyExists).isFalse();

		// When generic redis failure (module not loaded) occurs
		when(connection.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(new RuntimeException(
				"ERR unknown command 'FT.CREATE'"));
		boolean genericError = client.createIndexIfNotExists("test:idx", "test:doc:", 1536);
		assertThat(genericError).isFalse();
	}

	@Test
	@DisplayName("createIndexIfNotExists handles caused failures and lowercase already-exists markers")
	void createIndexHandlesCausedAndLowercaseMarkers() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);

		when(connection.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(
				new RuntimeException("creation failed",
						new RuntimeException((String) null)));
		assertThat(client.createIndexIfNotExists("idx", "doc:", 1536)).isFalse();

		when(connection.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(
				new RuntimeException("creation failed",
						new RuntimeException("Index already exists")));
		assertThat(client.createIndexIfNotExists("idx", "doc:", 1536)).isFalse();

		when(connection.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(new RuntimeException(
				"index already exists (code 42)"));
		assertThat(client.createIndexIfNotExists("idx", "doc:", 1536)).isFalse();
	}

	@Test
	@DisplayName("searchKnn maps raw FT.SEARCH rows with scores and fields")
	void searchKnnMapsRawRows() {
		Map<String, String> fields = new HashMap<>();
		fields.put("prompt_text", "How to reset password");
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(searchReply(1L, docRow("aegis:cache:doc:tenant1:doc123", "0.05", fields)));

		List<VectorSearchResult> results = client.searchKnn(
				"test:idx",
				"@owner_id:{tenant1}",
				new float[]{0.1f, 0.2f},
				1
		);
		assertThat(results).hasSize(1);
		VectorSearchResult res = results.getFirst();
		assertThat(res.docKey()).isEqualTo("aegis:cache:doc:tenant1:doc123");
		assertThat(res.distance()).isEqualTo(0.05);
		assertThat(res.similarityScore()).isBetween(0.949f, 0.951f);
		assertThat(res.fields().get("prompt_text")).isEqualTo("How to reset password");
	}

	@Test
	@DisplayName("searchKnn sends TIMEOUT and WITHSCORES bounds with the query")
	void searchKnnSendsTimeoutAndWithScores() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(searchReply(0L));
		client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);

		ArgumentCaptor<byte[][]> args = ArgumentCaptor.forClass(byte[][].class);
		verify(connection, atLeastOnce()).execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), args.capture());
		boolean hasTimeout = args.getAllValues().stream().anyMatch(a -> {
			List<String> parts = new ArrayList<>();
			for (byte[] part : a) {
				parts.add(new String(part, StandardCharsets.UTF_8));
			}
			int timeoutIdx = parts.indexOf("TIMEOUT");
			int scoresIdx = parts.indexOf("WITHSCORES");
			return timeoutIdx >= 0 && timeoutIdx + 1 < parts.size() && !parts.get(timeoutIdx + 1).isBlank()
					&& scoresIdx >= 0;
		});
		assertThat(hasTimeout).isTrue();
	}

	@Test
	@DisplayName("searchKnn maps RESP3 flattened-map replies with double scores")
	void searchKnnMapsResp3FlattenedMap() {
		List<Object> rowFields = new ArrayList<>();
		rowFields.add("prompt_text".getBytes(StandardCharsets.UTF_8));
		rowFields.add("How to reset password".getBytes(StandardCharsets.UTF_8));
		List<Object> row = new ArrayList<>();
		row.add("aegis:cache:doc:9".getBytes(StandardCharsets.UTF_8));
		row.add(0.04d);
		row.add("extra_attributes".getBytes(StandardCharsets.UTF_8));
		row.add(rowFields);
		List<Object> rows = new ArrayList<>();
		rows.add(row);
		List<Object> flatMap = new ArrayList<>();
		flatMap.add("attributes".getBytes(StandardCharsets.UTF_8));
		flatMap.add(new ArrayList<>());
		flatMap.add("results".getBytes(StandardCharsets.UTF_8));
		flatMap.add(rows);
		flatMap.add("total_results".getBytes(StandardCharsets.UTF_8));
		flatMap.add(1L);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(flatMap);

		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().docKey()).isEqualTo("aegis:cache:doc:9");
		assertThat(results.getFirst().distance()).isEqualTo(0.04);
		assertThat(results.getFirst().fields().get("prompt_text")).isEqualTo("How to reset password");
	}

	@Test
	@DisplayName("searchKnn returns empty when a RESP3 map carries no results section")
	void searchKnnEmptyResp3Map() {
		List<Object> flatMap = new ArrayList<>();
		flatMap.add("attributes".getBytes(StandardCharsets.UTF_8));
		flatMap.add(new ArrayList<>());
		flatMap.add("total_results".getBytes(StandardCharsets.UTF_8));
		flatMap.add(0L);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(flatMap);

		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
	}

	@Test
	@DisplayName("searchKnn maps RESP3 edge rows: non-list, empty, single-cell and marker-less rows")
	void searchKnnMapsResp3EdgeRows() {
		List<Object> goodFields = new ArrayList<>();
		goodFields.add("prompt_text".getBytes(StandardCharsets.UTF_8));
		goodFields.add("hi".getBytes(StandardCharsets.UTF_8));
		List<Object> goodRow = new ArrayList<>();
		goodRow.add("doc-good".getBytes(StandardCharsets.UTF_8));
		goodRow.add(0.07d);
		List<Object> rows = new ArrayList<>();
		rows.add("not-a-row");
		rows.add(new ArrayList<>());
		rows.add(new ArrayList<>(List.of("doc-lonely".getBytes(StandardCharsets.UTF_8))));
		rows.add(goodRow);
		List<Object> flatMap = new ArrayList<>();
		flatMap.add("results".getBytes(StandardCharsets.UTF_8));
		flatMap.add(rows);
		flatMap.add("results".getBytes(StandardCharsets.UTF_8));
		flatMap.add("stray");
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(flatMap);

		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 5);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().docKey()).isEqualTo("doc-good");
		assertThat(results.getFirst().fields()).isEmpty();
	}

	@Test
	@DisplayName("searchKnn prefers the __embedding_score field over a nan positional score")
	void searchKnnPrefersVectorScoreField() {
		// Live RediSearch shape for pure-vector queries: positional WITHSCORES is nan (text score),
		// the true KNN distance rides in __embedding_score.
		Map<String, String> fields = new HashMap<>();
		fields.put("__embedding_score", "0.013");
		fields.put("prompt_text", "How to reset password");
		List<Object> attrs = new ArrayList<>();
		fields.forEach((name, value) -> {
			attrs.add(name.getBytes(StandardCharsets.UTF_8));
			attrs.add(value.getBytes(StandardCharsets.UTF_8));
		});
		List<Object> reply = new ArrayList<>();
		reply.add(1L);
		reply.add("aegis:cache:doc:live".getBytes(StandardCharsets.UTF_8));
		reply.add("nan".getBytes(StandardCharsets.UTF_8));
		reply.add(attrs);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(reply);

		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().docKey()).isEqualTo("aegis:cache:doc:live");
		assertThat(results.getFirst().distance()).isEqualTo(0.013);
	}

	@Test
	@DisplayName("searchKnn skips rows with nan or missing scores instead of failing the reply")
	void searchKnnSkipsNanRows() {
		Map<String, String> goodFields = Map.of("prompt_text", "fine");
		List<Object> reply = searchReply(2L,
				docRow("aegis:cache:doc:bad", "nan", Map.of("prompt_text", "bad")),
				docRow("aegis:cache:doc:good", "0.02", goodFields));
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(reply);

		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 2);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().docKey()).isEqualTo("aegis:cache:doc:good");
	}

	@Test
	@DisplayName("searchKnn degrades to empty on empty and null replies")
	void searchKnnEmptyReplies() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(searchReply(0L));
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(null);
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn("not-a-list");
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
	}

	@Test
	@DisplayName("searchKnn maps null field values to empty strings")
	void searchKnnMapsNullFieldValues() {
		List<Object> attrs = new ArrayList<>();
		attrs.add("maybe".getBytes(StandardCharsets.UTF_8));
		attrs.add(null);
		attrs.add(null);
		attrs.add("v".getBytes(StandardCharsets.UTF_8));
		List<Object> reply = new ArrayList<>();
		reply.add(1L);
		reply.add("doc1".getBytes(StandardCharsets.UTF_8));
		reply.add("0.01".getBytes(StandardCharsets.UTF_8));
		reply.add(attrs);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(reply);

		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().fields().get("maybe")).isEmpty();
	}

	@Test
	@DisplayName("searchKnn failure carrying a cause chain still degrades to empty without throwing")
	void searchKnnFailureWithCause() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class))).thenThrow(
				new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")));
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
	}

	@Test
	@DisplayName("searchKnn retries once on desync-signature failures and returns the retried results")
	void searchKnnRetriesDesyncOnce() {
		Map<String, String> fields = Map.of("prompt_text", "fine");
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenThrow(new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")))
				.thenReturn(searchReply(1L, docRow("aegis:cache:doc:1", "0.05", fields)));

		List<VectorSearchResult> results =
				client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);

		assertThat(results).hasSize(1);
		verify(connection, times(2))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn fails closed when the retry also fails")
	void searchKnnRetryExhaustionFailsClosed() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class))).thenThrow(
				new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")));

		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		verify(connection, times(2))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn never retries deterministic failures")
	void searchKnnNeverRetriesDeterministicFailures() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenThrow(new RuntimeException("OOM command not allowed when used memory > maxmemory"))
				.thenThrow(new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value"))
				.thenThrow(new RuntimeException("NOSCRIPT No matching script. Please use EVAL."))
				.thenThrow(new RuntimeException("no such index: idx"))
				.thenThrow(new RuntimeException("syntax error near '['"));

		for (int i = 0; i < 5; i++) {
			assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		}
		verify(connection, times(5))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn retries timeout and connection-loss causes")
	void searchKnnRetriesTimeoutAndConnectionLoss() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenThrow(new RuntimeException("command timed out",
						new TimeoutException("timed out")))
				.thenThrow(new RuntimeException("Redis connection failed",
						new RedisConnectionException("connection lost")))
				.thenThrow(new RuntimeException((String) null,
						new UnsupportedOperationException((String) null)))
				.thenReturn(searchReply(0L));

		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		verify(connection, times(2))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));

		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		verify(connection, times(4))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn stops retrying once the retry budget is exhausted")
	void searchKnnStopsRetryingWhenBudgetExhausted() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class))).thenThrow(
				new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")));

		// Fresh client starts with 50 tokens: each failing lookup consumes one via its single retry.
		// One hundred lookups also exercise the periodic warn-budget branch.
		for (int i = 0; i < 100; i++) {
			assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		}
		clearInvocations(connection);

		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		verify(connection, times(1))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn refills the retry budget as successes accumulate")
	void searchKnnRefillsBudgetOnSuccess() {
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(searchReply(0L));
		for (int i = 0; i < 10; i++) {
			assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		}
		verify(connection, times(10))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn still fails closed when the meter registry itself is broken")
	void searchKnnSurvivesBrokenMeterRegistry() {
		MeterRegistry broken = mock(MeterRegistry.class);
		RediSearchVectorClient unmeteredClient = new RediSearchVectorClient(factory, broken);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenThrow(new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")))
				.thenReturn(searchReply(0L));

		assertThat(unmeteredClient.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		verify(connection, times(2))
				.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("searchKnn records failure metrics on a provided registry")
	void searchKnnUsesProvidedRegistry() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RediSearchVectorClient meteredClient = new RediSearchVectorClient(factory, registry);
		when(connection.execute(eq("FT.SEARCH"), any(NestedMultiOutput.class), any(byte[][].class))).thenThrow(
				new RuntimeException("Unknown redis exception",
						new UnsupportedOperationException("ValueOutput does not support set(long)")));

		assertThat(meteredClient.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();
		assertThat(registry.find("aegis.cache.vector.search.failures").counter()).isNotNull();
		assertThat(registry.find("aegis.cache.vector.search.retries").counter()).isNotNull();
	}

	@Test
	@DisplayName("saveVectorDocument refuses zero-norm vectors without touching Redis")
	void saveVectorDocumentRejectsZeroNorm() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);
		Map<byte[], byte[]> fields = new HashMap<>();
		fields.put("embedding".getBytes(StandardCharsets.UTF_8), new byte[12]);
		fields.put("owner_id".getBytes(StandardCharsets.UTF_8), "t".getBytes(StandardCharsets.UTF_8));

		client.saveVectorDocument("aegis:cache:doc:zero", fields, Duration.ofMinutes(10));

		verify(connection, never()).hashCommands();
		verify(hashCommands, never()).hMSet(any(), any());
	}

	@Test
	@DisplayName("saveVectorDocument refuses malformed embedding blobs without touching Redis")
	void saveVectorDocumentRejectsMalformedBlob() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);
		Map<byte[], byte[]> fields = new HashMap<>();
		fields.put("embedding".getBytes(StandardCharsets.UTF_8), new byte[3]);
		fields.put("owner_id".getBytes(StandardCharsets.UTF_8), "t".getBytes(StandardCharsets.UTF_8));

		client.saveVectorDocument("aegis:cache:doc:bad", fields, Duration.ofMinutes(10));

		verify(hashCommands, never()).hMSet(any(), any());
	}

	@Test
	@DisplayName("saveVectorDocument stores documents carrying healthy embeddings")
	void saveVectorDocumentStoresHealthyEmbedding() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);
		byte[] blob = new byte[8];
		blob[0] = 1;
		Map<byte[], byte[]> fields = new HashMap<>();
		fields.put("embedding".getBytes(StandardCharsets.UTF_8), blob);

		client.saveVectorDocument("aegis:cache:doc:ok", fields, Duration.ofMinutes(10));

		verify(hashCommands).hMSet(eq("aegis:cache:doc:ok".getBytes(StandardCharsets.UTF_8)), eq(fields));
	}

	@Test
	@DisplayName("saveVectorDocument and deleteDocument execute properly on Redis connection")
	void saveAndDeleteDocument() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory, null);

		Map<byte[], byte[]> fields = Map.of("k".getBytes(), "v".getBytes());
		client.saveVectorDocument("aegis:cache:doc:1", fields, Duration.ofMinutes(10));
		verify(hashCommands).hMSet(eq("aegis:cache:doc:1".getBytes(StandardCharsets.UTF_8)), eq(fields));
		verify(keyCommands).expire(eq("aegis:cache:doc:1".getBytes(StandardCharsets.UTF_8)), eq(600L));

		// Without TTL or null/zero/negative TTL
		client.saveVectorDocument("aegis:cache:doc:2", fields, null);
		client.saveVectorDocument("aegis:cache:doc:3", fields, Duration.ZERO);
		client.saveVectorDocument("aegis:cache:doc:4", fields, Duration.ofSeconds(-5));

		client.deleteDocument("aegis:cache:doc:1");
		verify(keyCommands).del(eq("aegis:cache:doc:1".getBytes(StandardCharsets.UTF_8)));

		client.dropIndex("test:idx", true);
		client.dropIndex("test:idx", false);
		verify(connection, times(2)).execute(eq("FT.DROPINDEX"), any(byte[][].class));

		// Exception on dropIndex
		doThrow(new RuntimeException("drop err")).when(connection)
				.execute(eq("FT.DROPINDEX"), any(byte[][].class));
		client.dropIndex("test:idx", false);

		// DataAccessException on save and delete
		doThrow(new org.springframework.data.redis.RedisSystemException("save err", new RuntimeException()))
				.when(hashCommands).hMSet(any(), any());
		client.saveVectorDocument("aegis:cache:doc:err", fields, Duration.ofMinutes(1));

		doThrow(new org.springframework.data.redis.RedisSystemException("del err", new RuntimeException()))
				.when(keyCommands).del(any(byte[].class));
		client.deleteDocument("aegis:cache:doc:err");
	}

	@Test
	@DisplayName("vectorDimensionOf reads the VECTOR field dim from FT.INFO")
	void vectorDimensionOfReadsDim() {
		List<Object> attributes = List.of(
				"identifier".getBytes(StandardCharsets.UTF_8),
				"owner_id".getBytes(StandardCharsets.UTF_8),
				"attribute".getBytes(StandardCharsets.UTF_8),
				"owner_id".getBytes(StandardCharsets.UTF_8),
				"type".getBytes(StandardCharsets.UTF_8),
				"TAG".getBytes(StandardCharsets.UTF_8),
				"SEPARATOR".getBytes(StandardCharsets.UTF_8),
				",".getBytes(StandardCharsets.UTF_8),
				"identifier".getBytes(StandardCharsets.UTF_8),
				"embedding".getBytes(StandardCharsets.UTF_8),
				"attribute".getBytes(StandardCharsets.UTF_8),
				"embedding".getBytes(StandardCharsets.UTF_8),
				"type".getBytes(StandardCharsets.UTF_8),
				"VECTOR".getBytes(StandardCharsets.UTF_8),
				"algorithm".getBytes(StandardCharsets.UTF_8),
				"HNSW".getBytes(StandardCharsets.UTF_8),
				"data_type".getBytes(StandardCharsets.UTF_8),
				"FLOAT32".getBytes(StandardCharsets.UTF_8),
				"dim".getBytes(StandardCharsets.UTF_8),
				768L,
				"distance_metric".getBytes(StandardCharsets.UTF_8),
				"COSINE".getBytes(StandardCharsets.UTF_8)
		);
		List<Object> info = List.of(
				"aegis:cache:idx".getBytes(StandardCharsets.UTF_8),
				"index_name".getBytes(StandardCharsets.UTF_8),
				"index_options".getBytes(StandardCharsets.UTF_8),
				"".getBytes(StandardCharsets.UTF_8),
				"index_definition".getBytes(StandardCharsets.UTF_8),
				"key_type".getBytes(StandardCharsets.UTF_8),
				"HASH".getBytes(StandardCharsets.UTF_8),
				"prefixes".getBytes(StandardCharsets.UTF_8),
				List.of("aegis:cache:doc:".getBytes(StandardCharsets.UTF_8)),
				"default_score".getBytes(StandardCharsets.UTF_8),
				1L,
				"attributes".getBytes(StandardCharsets.UTF_8),
				attributes
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(info);

		assertThat(client.vectorDimensionOf("aegis:cache:idx")).isEqualTo(768);
	}

	@Test
	@DisplayName("vectorDimensionOf reads dim from a flattened RESP3-style map reply")
	void vectorDimensionOfReadsFlattenedMap() {
		// RESP3 FT.INFO arrives as a map; NestedMultiOutput flattens it to key/value pairs inline.
		List<Object> flatMap = List.of(
				"index_name".getBytes(StandardCharsets.UTF_8),
				"aegis:cache:idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				List.of(
						"identifier".getBytes(StandardCharsets.UTF_8),
						"embedding".getBytes(StandardCharsets.UTF_8),
						"type".getBytes(StandardCharsets.UTF_8),
						"VECTOR".getBytes(StandardCharsets.UTF_8),
						"dim".getBytes(StandardCharsets.UTF_8),
						768L,
						"distance_metric".getBytes(StandardCharsets.UTF_8),
						"COSINE".getBytes(StandardCharsets.UTF_8)
				)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(flatMap);
		assertThat(client.vectorDimensionOf("aegis:cache:idx")).isEqualTo(768);
	}

	@Test
	@DisplayName("vectorDimensionOf decodes FT.INFO with a numeric-capable output")
	void vectorDimensionOfUsesNumericCapableOutput() {
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(List.of());
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);
		verify(connection).execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class));
	}

	@Test
	@DisplayName("vectorDimensionOf returns -1 on empty, truncated and non-list attribute sections")
	void vectorDimensionOfHandlesTruncatedInfo() {
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(List.of());
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		List<Object> truncated = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(truncated);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		List<Object> nonListAttrs = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				"not-a-list".getBytes(StandardCharsets.UTF_8)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(nonListAttrs);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		List<Object> emptyAttrs = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				List.of()
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(emptyAttrs);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);
	}

	@Test
	@DisplayName("vectorDimensionOf returns -1 when no VECTOR field or a non-numeric dim is present")
	void vectorDimensionOfHandlesNonVectorInfo() {
		List<Object> tagOnly = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				List.of(
						"identifier".getBytes(StandardCharsets.UTF_8),
						"owner_id".getBytes(StandardCharsets.UTF_8),
						"attribute".getBytes(StandardCharsets.UTF_8),
						"owner_id".getBytes(StandardCharsets.UTF_8),
						"type".getBytes(StandardCharsets.UTF_8),
						"TAG".getBytes(StandardCharsets.UTF_8)
				)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(tagOnly);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		List<Object> stringDim = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				List.of(
						"identifier".getBytes(StandardCharsets.UTF_8),
						"embedding".getBytes(StandardCharsets.UTF_8),
						"attribute".getBytes(StandardCharsets.UTF_8),
						"embedding".getBytes(StandardCharsets.UTF_8),
						"type".getBytes(StandardCharsets.UTF_8),
						"VECTOR".getBytes(StandardCharsets.UTF_8),
						"algorithm".getBytes(StandardCharsets.UTF_8),
						"HNSW".getBytes(StandardCharsets.UTF_8),
						"data_type".getBytes(StandardCharsets.UTF_8),
						"FLOAT32".getBytes(StandardCharsets.UTF_8),
						"dim".getBytes(StandardCharsets.UTF_8),
						"768".getBytes(StandardCharsets.UTF_8),
						"distance_metric".getBytes(StandardCharsets.UTF_8),
						"COSINE".getBytes(StandardCharsets.UTF_8)
				)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(stringDim);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		List<Object> danglingDim = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"attributes".getBytes(StandardCharsets.UTF_8),
				List.of(
						"identifier".getBytes(StandardCharsets.UTF_8),
						"embedding".getBytes(StandardCharsets.UTF_8),
						"type".getBytes(StandardCharsets.UTF_8),
						"VECTOR".getBytes(StandardCharsets.UTF_8),
						"dim".getBytes(StandardCharsets.UTF_8)
				)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(danglingDim);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);
	}

	@Test
	@DisplayName("vectorDimensionOf returns -1 when the index does not exist or has no vector field")
	void vectorDimensionOfHandlesAbsence() {
		// Non-list FT.INFO response (e.g. Redis returned null)
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(null);
		assertThat(client.vectorDimensionOf("missing-idx")).isEqualTo(-1);

		// FT.INFO with no "attributes" key
		List<Object> info = List.of(
				"idx".getBytes(StandardCharsets.UTF_8),
				"index_name".getBytes(StandardCharsets.UTF_8)
		);
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenReturn(info);
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);

		// Exception path
		when(connection.execute(eq("FT.INFO"), any(NestedMultiOutput.class), any(byte[][].class)))
				.thenThrow(new RuntimeException("Redis unavailable"));
		assertThat(client.vectorDimensionOf("idx")).isEqualTo(-1);
	}

}
