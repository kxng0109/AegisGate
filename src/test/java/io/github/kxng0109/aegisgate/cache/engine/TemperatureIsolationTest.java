package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.VectorSearchResult;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Temperature isolation: symmetric gate and L2 temperature tag filter")
class TemperatureIsolationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private CachePolicyEngine policyEngine;
	private AegisCacheProperties properties;
	private RediSearchVectorClient vectorClient;
	private EmbeddingService embeddingService;
	private CacheGuardrails guardrails;
	private RedisSemanticVectorCache l2;

	private static OpenAiChatRequest req(Double temperature) {
		return new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", MAPPER.valueToTree("Hello"))),
				temperature, null, null, null, null, true, null
		);
	}

	private static CompoundCacheKey key() {
		return new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "Hello", "", "", "hash123");
	}

	@BeforeEach
	void setUp() {
		properties = new AegisCacheProperties();
		assertThat(properties.getSemantic().getTemperatureFloor()).isEqualTo(0.1);
		properties.getSemantic().setEnabled(true);
		policyEngine = new CachePolicyEngine(properties);
		vectorClient = mock(RediSearchVectorClient.class);
		embeddingService = mock(EmbeddingService.class);
		guardrails = mock(CacheGuardrails.class);
		l2 = new RedisSemanticVectorCache(vectorClient, embeddingService, guardrails, properties);
		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
	}

	@Test
	@DisplayName("symmetric gate: lookup-bypass implies store-skip for high-T without opt-in")
	void symmetricGate() {
		MockHttpServletRequest http = new MockHttpServletRequest();
		assertThat(policyEngine.shouldEvaluateCache(req(1.0), http)).isFalse();
		assertThat(policyEngine.shouldStoreInCache(req(1.0), http)).isFalse();
		assertThat(policyEngine.shouldEvaluateCache(req(0.1), http)).isTrue();
		assertThat(policyEngine.shouldStoreInCache(req(0.1), http)).isTrue();
		assertThat(policyEngine.shouldEvaluateCache(req(0.11), http)).isFalse();
		assertThat(policyEngine.shouldStoreInCache(req(0.11), http)).isFalse();
		http.addHeader("X-Aegis-Cache-Stochastic", "true");
		assertThat(policyEngine.shouldEvaluateCache(req(1.0), http)).isTrue();
		assertThat(policyEngine.shouldStoreInCache(req(1.0), http)).isTrue();
		MockHttpServletRequest http2 = new MockHttpServletRequest();
		assertThat(policyEngine.shouldEvaluateCache(req(null), http2)).isTrue();
		assertThat(policyEngine.shouldStoreInCache(req(null), http2)).isTrue();
	}

	@Test
	@DisplayName("L2 filter: non-null temperature injects temperature tag clause; null omits it")
	@SuppressWarnings("unchecked")
	void l2FilterClause() {
		when(vectorClient.searchKnn(anyString(), anyString(), any(), anyInt()))
				.thenReturn(List.of(new VectorSearchResult(
						"doc:1", 0.05f,
						Map.of("prompt_text", "Hello", "response_json", "{}")
				)));
		when(guardrails.validateSemanticMatch(anyString(), anyString(), anyBoolean(), anyBoolean()))
				.thenReturn(true);

		l2.findSemanticMatch(key(), 0.0);
		ArgumentCaptor<String> firstFilter = ArgumentCaptor.forClass(String.class);
		verify(vectorClient, times(1)).searchKnn(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				firstFilter.capture(),
				any(),
				eq(1)
		);
		assertThat(firstFilter.getValue()).contains("@temperature:{");
		assertThat(firstFilter.getValue()).contains(RediSearchVectorClient.escapeTag("0.0"));

		l2.findSemanticMatch(key(), null);
		ArgumentCaptor<String> secondFilter = ArgumentCaptor.forClass(String.class);
		verify(vectorClient, times(2)).searchKnn(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				secondFilter.capture(),
				any(),
				eq(1)
		);
		assertThat(secondFilter.getValue()).doesNotContain("@temperature:{");
	}

	@Test
	@DisplayName("store with temperature persists the tag; null omits it for backward compatibility")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void storeTemperatureTag() {
		ArgumentCaptor<Map> firstFields = ArgumentCaptor.forClass(Map.class);
		ArgumentCaptor<Map> secondFields = ArgumentCaptor.forClass(Map.class);

		l2.storeSemanticEntry(key(), "{}", 5, 5, 10, Duration.ofMinutes(5), 0.0);
		verify(vectorClient, times(1)).saveVectorDocument(anyString(), firstFields.capture(), any());
		boolean hasTemp = firstFields.getValue().keySet().stream()
		                             .anyMatch(k -> new String((byte[]) k).equals("temperature"));
		assertThat(hasTemp).isTrue();

		l2.storeSemanticEntry(key(), "{}", 5, 5, 10, Duration.ofMinutes(5), null);
		verify(vectorClient, times(2)).saveVectorDocument(anyString(), secondFields.capture(), any());
		boolean hasTempAfterNull = secondFields.getValue().keySet().stream()
		                                       .anyMatch(k -> new String((byte[]) k).equals("temperature"));
		assertThat(hasTempAfterNull).isFalse();
	}
}
