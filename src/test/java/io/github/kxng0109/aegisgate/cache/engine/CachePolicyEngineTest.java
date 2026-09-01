package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CachePolicyEngine")
class CachePolicyEngineTest {

	private final AegisCacheProperties properties = new AegisCacheProperties();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);

	@Test
	@DisplayName("shouldEvaluateCache respects RFC 9111 headers, extension headers, and temperature floor")
	void shouldEvaluateCache() {
		OpenAiChatRequest normalReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.0,
				null,
				null,
				null,
				null,
				true,
				null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		// Default: enabled
		assertThat(policyEngine.shouldEvaluateCache(normalReq, httpReq)).isTrue();

		// Cache-Control: no-cache / no-store / max-age=0
		MockHttpServletRequest ccNoCache = new MockHttpServletRequest();
		ccNoCache.addHeader("Cache-Control", "no-cache");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccNoCache)).isFalse();

		MockHttpServletRequest ccNoStore = new MockHttpServletRequest();
		ccNoStore.addHeader("Cache-Control", "no-store");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccNoStore)).isFalse();

		// X-Aegis-No-Cache: true
		MockHttpServletRequest aegisNoCache = new MockHttpServletRequest();
		aegisNoCache.addHeader("X-Aegis-No-Cache", "true");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, aegisNoCache)).isFalse();

		// Cache-Control: max-age=0
		MockHttpServletRequest ccMaxAge = new MockHttpServletRequest();
		ccMaxAge.addHeader("Cache-Control", "max-age=0");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccMaxAge)).isFalse();

		// X-Aegis-Cache-Mode: write-only
		MockHttpServletRequest aegisWriteOnly = new MockHttpServletRequest();
		aegisWriteOnly.addHeader("X-Aegis-Cache-Mode", "write-only");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, aegisWriteOnly)).isFalse();

		// X-Aegis-Cache-Mode: bypass in shouldStoreInCache
		MockHttpServletRequest storeBypass = new MockHttpServletRequest();
		storeBypass.addHeader("X-Aegis-Cache-Mode", "bypass");
		assertThat(policyEngine.shouldStoreInCache(normalReq, storeBypass)).isFalse();

		// High temperature with false stochastic header
		MockHttpServletRequest nonStochastic = new MockHttpServletRequest();
		nonStochastic.addHeader("X-Aegis-Cache-Stochastic", "false");
		OpenAiChatRequest highTempReq2 = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.7,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq2, nonStochastic)).isFalse();

		// High temperature without stochastic header
		MockHttpServletRequest noStochasticHeader = new MockHttpServletRequest();
		OpenAiChatRequest highTempReq3 = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.8,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq3, noStochasticHeader)).isFalse();

		// shouldStoreInCache with read-write mode
		MockHttpServletRequest readWrite = new MockHttpServletRequest();
		readWrite.addHeader("X-Aegis-Cache-Mode", "read-write");
		assertThat(policyEngine.shouldStoreInCache(normalReq, readWrite)).isTrue();

		// Out of range upper threshold (> 1.00)
		MockHttpServletRequest highThreshReq = new MockHttpServletRequest();
		highThreshReq.addHeader("X-Aegis-Semantic-Threshold", "1.50");
		assertThat(policyEngine.resolveSimilarityThreshold(highThreshReq)).isEqualTo(0.90);

		// High temperature (0.7 > 0.1) without stochastic override -> bypass
		OpenAiChatRequest highTempReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.7,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq, httpReq)).isFalse();

		// High temperature with X-Aegis-Cache-Stochastic: true -> allowed
		MockHttpServletRequest stochasticReq = new MockHttpServletRequest();
		stochasticReq.addHeader("X-Aegis-Cache-Stochastic", "true");
		assertThat(policyEngine.shouldEvaluateCache(highTempReq, stochasticReq)).isTrue();
	}

	@Test
	@DisplayName("shouldStoreInCache respects no-store and read-only directives")
	void shouldStoreInCache() {
		OpenAiChatRequest normalReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.0,
				null,
				null,
				null,
				null,
				true,
				null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();
		assertThat(policyEngine.shouldStoreInCache(normalReq, httpReq)).isTrue();

		MockHttpServletRequest ccNoStore = new MockHttpServletRequest();
		ccNoStore.addHeader("Cache-Control", "no-store");
		assertThat(policyEngine.shouldStoreInCache(normalReq, ccNoStore)).isFalse();

		MockHttpServletRequest readOnly = new MockHttpServletRequest();
		readOnly.addHeader("X-Aegis-Cache-Mode", "read-only");
		assertThat(policyEngine.shouldStoreInCache(normalReq, readOnly)).isFalse();

		properties.setEnabled(false);
		assertThat(policyEngine.shouldEvaluateCache(normalReq, httpReq)).isFalse();
		assertThat(policyEngine.shouldStoreInCache(normalReq, httpReq)).isFalse();
		properties.setEnabled(true);
	}

	@Test
	@DisplayName("resolveScope and resolveSimilarityThreshold parse request header overrides and fallbacks")
	void resolveScopeAndThreshold() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("X-Aegis-Cache-Scope", "USER");
		req.addHeader("X-Aegis-Semantic-Threshold", "0.95");

		assertThat(policyEngine.resolveScope(req)).isEqualTo(CacheScope.USER);
		assertThat(policyEngine.resolveSimilarityThreshold(req)).isEqualTo(0.95);

		// Invalid or out-of-range overrides fall back to defaults
		MockHttpServletRequest invalidReq = new MockHttpServletRequest();
		invalidReq.addHeader("X-Aegis-Cache-Scope", "INVALID_SCOPE");
		invalidReq.addHeader("X-Aegis-Semantic-Threshold", "0.20"); // below 0.50 min
		assertThat(policyEngine.resolveScope(invalidReq)).isEqualTo(CacheScope.TENANT);
		assertThat(policyEngine.resolveSimilarityThreshold(invalidReq)).isEqualTo(0.90);

		MockHttpServletRequest malformedReq = new MockHttpServletRequest();
		malformedReq.addHeader("X-Aegis-Semantic-Threshold", "abc");
		assertThat(policyEngine.resolveSimilarityThreshold(malformedReq)).isEqualTo(0.90);
	}
}
