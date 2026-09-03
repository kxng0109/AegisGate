package io.github.kxng0109.aegisgate.cache.engine.l2;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.cache.engine.CacheGuardrails;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.aegisgate.proxy.embeddings.VectorEncodingUtils;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RedisSemanticVectorCache")
class RedisSemanticVectorCacheTest {

	private final RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final CacheGuardrails guardrails = new CacheGuardrails();
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private RedisSemanticVectorCache cache;

	@BeforeEach
	void setUp() {
		properties.getSemantic().setEnabled(true);
		properties.getSemantic().setSimilarityThreshold(0.90);
		cache = new RedisSemanticVectorCache(vectorClient, embeddingService, guardrails, properties);
	}

	@Test
	@DisplayName("findSemanticMatch returns valid CacheEntry when similarity exceeds threshold and guardrails pass")
	void findSemanticMatchSuccess() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"aegis:cache:doc:tenant1:doc1",
				0.05, // Distance = 0.05 -> Sim = 0.95 >= 0.90
				Map.of(
						"prompt_text", "I forgot my password",
						"response_json", "{\"content\":\"Click reset password\"}",
						"prompt_tokens", "10",
						"completion_tokens", "20",
						"total_tokens", "30"
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key);
		assertThat(entry).isNotNull();
		assertThat(entry.promptText()).isEqualTo("I forgot my password");
		assertThat(entry.similarityScore()).isBetween(0.949f, 0.951f);
		assertThat(entry.promptTokens()).isEqualTo(10);
	}

	@Test
	@DisplayName("findSemanticMatch returns null when similarity is below threshold")
	void findSemanticMatchBelowThreshold() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "What is Python?"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult lowMatch = new VectorSearchResult(
				"aegis:cache:doc:tenant1:doc2",
				0.25, // Distance = 0.25 -> Sim = 0.75 < 0.90
				Map.of("prompt_text", "What is Java?", "response_json", "{}")
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(lowMatch));

		CacheEntry entry = cache.findSemanticMatch(key);
		assertThat(entry).isNull();
	}

	@Test
	@DisplayName("storeSemanticEntry generates embedding and writes document with fields to Redis")
	void storeSemanticEntry() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "prefixHash", "sysHash", "How to deploy"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		cache.storeSemanticEntry(key, "{\"choices\":[]}", 15, 30, 45, Duration.ofHours(1));

		verify(vectorClient).saveVectorDocument(
				startsWith("aegis:cache:doc:tenant1:"),
				anyMap(),
				eq(Duration.ofHours(1))
		);
	}

	@Test
	@DisplayName("findSemanticMatch supports Base64 and List embedding vectors")
	void embeddingVectorFormatSupport() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		// Base64 embedding
		String b64 = VectorEncodingUtils.encodeToBase64(new float[]{0.5f, -0.5f});
		EmbeddingResponse b64Embedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, b64)), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(b64Embedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1",
				0.02,
				Map.of(
						"prompt_text",
						"Hello",
						"created_at",
						"2026-09-01T12:00:00Z"
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key);
		assertThat(entry).isNotNull();
		assertThat(entry.similarityScore()).isBetween(0.979f, 0.981f);

		// List of numbers embedding
		EmbeddingResponse listEmbedding = new EmbeddingResponse(
				"list",
				List.of(new EmbeddingData("embedding", 0, List.of(0.1, "not-number"))),
				"text-embedding-3-small",
				null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(listEmbedding);
		CacheEntry entry2 = cache.findSemanticMatch(key);
		assertThat(entry2).isNotNull();

		// Null embedding object in EmbeddingData
		EmbeddingResponse nullEmbedding = new EmbeddingResponse(
				"list", List.of(new EmbeddingData("embedding", 0, null)), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(nullEmbedding);
		assertThat(cache.findSemanticMatch(key)).isNull();
	}

	@Test
	@DisplayName("findSemanticMatch builds filter query with prefixHash and systemPromptHash and parses invalid dates safely")
	void filterQueryWithHashesAndInvalidDates() {
		CompoundCacheKey fullKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "pref123", "sys123", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1", 0.01,
				Map.of(
						"prompt_text", "Hello",
						"created_at", "invalid-date-format",
						"prompt_tokens", "not-int",
						"completion_tokens", "not-int"
				)
		);
		when(vectorClient.searchKnn(anyString(), contains("prefix_hash"), any(), eq(1))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(fullKey);
		assertThat(entry).isNotNull();
		assertThat(entry.promptTokens()).isZero();
	}

	@Test
	@DisplayName("edge cases: disabled, blank prompt, empty search results, and guardrail failures")
	void edgeCases() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		// Disabled semantic cache
		properties.getSemantic().setEnabled(false);
		assertThat(cache.findSemanticMatch(key)).isNull();

		// Blank prompt
		properties.getSemantic().setEnabled(true);
		CompoundCacheKey blankKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "   "
		);
		assertThat(cache.findSemanticMatch(blankKey)).isNull();

		// Empty results
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of());
		assertThat(cache.findSemanticMatch(key)).isNull();

		// Guardrail mismatch (e.g. enable vs disable)
		CompoundCacheKey enableKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to enable 2FA"
		);
		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
		VectorSearchResult mismatch = new VectorSearchResult("doc2", 0.01, Map.of("prompt_text", "How to disable 2FA"));
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(mismatch));
		assertThat(cache.findSemanticMatch(enableKey)).isNull();
	}
}
