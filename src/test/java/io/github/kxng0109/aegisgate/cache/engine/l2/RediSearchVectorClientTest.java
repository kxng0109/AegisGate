package io.github.kxng0109.aegisgate.cache.engine.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("RediSearchVectorClient")
class RediSearchVectorClientTest {

	private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
	private final RedisConnection connection = mock(RedisConnection.class);
	private final RedisHashCommands hashCommands = mock(RedisHashCommands.class);
	private final RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);

	@BeforeEach
	void setUp() {
		when(factory.getConnection()).thenReturn(connection);
		when(connection.hashCommands()).thenReturn(hashCommands);
		when(connection.keyCommands()).thenReturn(keyCommands);
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
		RediSearchVectorClient client = new RediSearchVectorClient(factory);

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
	@DisplayName("searchKnn dispatches FT.SEARCH and correctly parses multi-document responses")
	void searchKnnParsing() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory);

		// Mock RediSearch wire response: [1, doc_key, [attr1, val1, attr2, val2, score, 0.05]]
		List<Object> mockResponse = List.of(
				1L,
				"aegis:cache:doc:tenant1:doc123".getBytes(StandardCharsets.UTF_8),
				List.of(
						"prompt_text".getBytes(StandardCharsets.UTF_8),
						"How to reset password".getBytes(StandardCharsets.UTF_8),
						"score".getBytes(StandardCharsets.UTF_8),
						"0.05".getBytes(StandardCharsets.UTF_8)
				)
		);
		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(mockResponse);

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
	@DisplayName("saveVectorDocument and deleteDocument execute properly on Redis connection")
	void saveAndDeleteDocument() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory);

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
		verify(connection).execute(eq("FT.DROPINDEX"), any(), any());

		client.dropIndex("test:idx", false);
		verify(connection).execute(eq("FT.DROPINDEX"), any());

		// Exception on dropIndex
		doThrow(new RuntimeException("drop err")).when(connection).execute(eq("FT.DROPINDEX"), any());
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
	@DisplayName("searchKnn edge cases: empty list, zero count, non-list, and malformed attributes")
	void searchKnnEdgeCases() {
		RediSearchVectorClient client = new RediSearchVectorClient(factory);

		// Exception during search
		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenThrow(new RuntimeException("search error"));
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(null);
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(List.of(0L));
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		// Non-list attrs and odd list sizing
		List<Object> nonListAttrs = List.of(1L, "docKey1".getBytes(), "notAList");
		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(nonListAttrs);
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).hasSize(1);

		// List with odd entries
		List<Object> oddAttrs = List.of(
				1L,
				"docKey2".getBytes(),
				List.of("dist".getBytes(), "0.08".getBytes(), "danglingKey".getBytes())
		);
		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(oddAttrs);
		List<VectorSearchResult> resOdd = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(resOdd).hasSize(1);
		assertThat(resOdd.getFirst().distance()).isEqualTo(0.08);

		// Malformed attribute score
		List<Object> malformedScoreResp = List.of(
				1L,
				"aegis:cache:doc:1".getBytes(StandardCharsets.UTF_8),
				List.of("score".getBytes(StandardCharsets.UTF_8), "not-a-number".getBytes(StandardCharsets.UTF_8))
		);
		when(connection.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(malformedScoreResp);
		List<VectorSearchResult> results = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().distance()).isEqualTo(1.0);
	}
}
