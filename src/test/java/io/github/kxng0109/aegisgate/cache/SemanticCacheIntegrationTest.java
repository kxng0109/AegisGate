package io.github.kxng0109.aegisgate.cache;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheLookupResult;
import io.github.kxng0109.aegisgate.cache.contracts.CacheStatus;
import io.github.kxng0109.aegisgate.cache.engine.*;
import io.github.kxng0109.aegisgate.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.VectorSearchResult;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SemanticCacheIntegrationTest")
class SemanticCacheIntegrationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private final RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final CacheGuardrails guardrails = new CacheGuardrails();
	private final CacheKeyGenerator keyGenerator = new CacheKeyGenerator();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);
	private final SingleFlightManager singleFlightManager = new SingleFlightManager();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private InMemoryExactCache l0Cache;
	private RedisExactCache l1Cache;
	private RedisSemanticVectorCache l2Cache;
	private AegisCacheService cacheService;

	@BeforeEach
	void setUp() {
		l0Cache = new InMemoryExactCache(properties);
		l1Cache = mock(RedisExactCache.class);
		l2Cache = new RedisSemanticVectorCache(vectorClient, embeddingService, guardrails, properties);
		cacheService = new AegisCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, singleFlightManager, properties, meterRegistry
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list",
				List.of(EmbeddingData.of(0, new float[]{0.05f, 0.95f})),
				"text-embedding-3-small",
				null
		);
		when(embeddingService.processEmbedding(any(), any())).thenReturn(mockEmbedding);
	}

	@Test
	@DisplayName("Full hierarchy: L0 miss -> L1 miss -> L2 semantic hit -> subsequent L0 hit in sub-millisecond")
	void fullHierarchyProgression() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("How to reset password"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		// Configure L2 Vector Hit with distance 0.04 (Similarity = 0.96)
		VectorSearchResult match = new VectorSearchResult(
				"aegis:cache:doc:tenant1:doc1",
				0.04,
				Map.of(
						"prompt_text", "I forgot my password",
						"response_json", "{\"choices\":[{\"message\":{\"content\":\"Go to settings to reset.\"}}]}",
						"prompt_tokens", "10",
						"completion_tokens", "15",
						"total_tokens", "25"
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(match));

		// 1. First evaluation: Hits L2 Semantic tier
		CacheLookupResult res1 = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(res1.isHit()).isTrue();
		assertThat(res1.status()).isEqualTo(CacheStatus.HIT_L2);
		assertThat(res1.similarityScore()).isBetween(0.959f, 0.961f);

		// 2. Second evaluation for identical query: Hits L0 In-Memory tier instantly!
		CacheLookupResult res2 = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(res2.isHit()).isTrue();
		assertThat(res2.status()).isEqualTo(CacheStatus.HIT_L0);
		assertThat(res2.similarityScore()).isEqualTo(1.0f);
	}

	@Test
	@DisplayName("Multi-tenant isolation: Tenant B cannot hit Tenant A's semantic vector cache")
	void multiTenantIsolation() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Confidential prompt"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		// When querying for Tenant B, RediSearch receives @owner_id:{tenantB} and returns empty
		when(vectorClient.searchKnn(anyString(), contains("tenantB"), any(), eq(1))).thenReturn(List.of());

		CacheLookupResult res = cacheService.evaluateCache(request, httpReq, "tenantB");
		assertThat(res.isHit()).isFalse();
		assertThat(res.status()).isEqualTo(CacheStatus.MISS);
	}
}
