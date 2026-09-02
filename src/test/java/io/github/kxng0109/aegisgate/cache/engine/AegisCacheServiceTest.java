package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheLookupResult;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CacheStatus;
import io.github.kxng0109.aegisgate.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AegisCacheService")
@SuppressWarnings("DataFlowIssue")
class AegisCacheServiceTest {

	private final InMemoryExactCache l0Cache = mock(InMemoryExactCache.class);
	private final RedisExactCache l1Cache = mock(RedisExactCache.class);
	private final RedisSemanticVectorCache l2Cache = mock(RedisSemanticVectorCache.class);
	private final CacheKeyGenerator keyGenerator = new CacheKeyGenerator();
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);
	private final SingleFlightManager singleFlightManager = new SingleFlightManager();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private AegisCacheService cacheService;

	@BeforeEach
	void setUp() {
		cacheService = new AegisCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, singleFlightManager, properties, meterRegistry
		);
	}

	@Test
	@DisplayName("evaluateCache returns HIT_L0 when in-memory cache resolves entry")
	void evaluateCacheL0Hit() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"Hello",
				"",
				"",
				"{}",
				5,
				5,
				10,
				Instant.now(),
				1.0f
		);
		when(l0Cache.get(anyString())).thenReturn(entry);

		CacheLookupResult result = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(result.isHit()).isTrue();
		assertThat(result.status()).isEqualTo(CacheStatus.HIT_L0);
		assertThat(result.entry()).isEqualTo(entry);
	}

	@Test
	@DisplayName("evaluateCache returns HIT_L1 when Redis exact cache resolves entry and backfills L0")
	void evaluateCacheL1Hit() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		when(l0Cache.get(anyString())).thenReturn(null);
		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"Hello",
				"",
				"",
				"{}",
				5,
				5,
				10,
				Instant.now(),
				1.0f
		);
		when(l1Cache.get(any())).thenReturn(entry);

		CacheLookupResult result = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(result.isHit()).isTrue();
		assertThat(result.status()).isEqualTo(CacheStatus.HIT_L1);
		verify(l0Cache).put(anyString(), eq(entry));
	}

	@Test
	@DisplayName("evaluateCache returns HIT_L2 when RediSearch vector cache resolves entry and backfills L0")
	void evaluateCacheL2Hit() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("How to reset password"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		when(l0Cache.get(anyString())).thenReturn(null);
		when(l1Cache.get(any())).thenReturn(null);

		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"I forgot my password",
				"",
				"",
				"{}",
				5,
				5,
				10,
				Instant.now(),
				0.94f
		);
		when(l2Cache.findSemanticMatch(any())).thenReturn(entry);

		CacheLookupResult result = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(result.isHit()).isTrue();
		assertThat(result.status()).isEqualTo(CacheStatus.HIT_L2);
		assertThat(result.similarityScore()).isEqualTo(0.94f);
		verify(l0Cache).put(anyString(), eq(entry));
	}

	@Test
	@DisplayName("evaluateCache returns MISS when all tiers miss")
	void evaluateCacheMiss() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		when(l0Cache.get(anyString())).thenReturn(null);
		when(l1Cache.get(any())).thenReturn(null);
		when(l2Cache.findSemanticMatch(any())).thenReturn(null);

		CacheLookupResult result = cacheService.evaluateCache(request, httpReq, "tenant1");
		assertThat(result.isHit()).isFalse();
		assertThat(result.status()).isEqualTo(CacheStatus.MISS);
	}

	@Test
	@DisplayName("storeResponse populates L0, L1, and L2 caches")
	void storeResponse() {
		OpenAiChatRequest request = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		cacheService.storeResponse(request, httpReq, "tenant1", "{\"content\":\"Hi\"}", 5, 10);

		verify(l0Cache).put(anyString(), any());
		verify(l1Cache).put(any(), any(), any());
		verify(l2Cache).storeSemanticEntry(any(), eq("{\"content\":\"Hi\"}"), eq(5), eq(10), eq(15), any());

		// When shouldStoreInCache is false
		properties.setEnabled(false);
		cacheService.storeResponse(request, httpReq, null, "{}", 1, 1);
		properties.setEnabled(true);

		// storeResponse with blank ownerId
		cacheService.storeResponse(request, httpReq, "   ", "{\"content\":\"Hi\"}", 2, 2);
	}

	@Test
	@DisplayName("init and purgeLocalCache execution")
	void initAndPurge() {
		cacheService.init();
		verify(l2Cache).initializeIndex();

		cacheService.purgeLocalCache();
		verify(l0Cache).invalidateAll();

		// Constructor with null MeterRegistry
		AegisCacheService nullRegistryService = new AegisCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, singleFlightManager, properties, null
		);
		assertThat(nullRegistryService).isNotNull();

		// init with disabled semantic cache
		properties.getSemantic().setEnabled(false);
		cacheService.init();
		properties.getSemantic().setEnabled(true);
	}

	@Test
	@DisplayName("evaluateCache handles null/empty owner, singleflight exception, and bypass with null model")
	void evaluateCacheEdgeCases() throws Exception {
		OpenAiChatRequest request = new OpenAiChatRequest(
				null,
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		// Bypass with null model and null owner
		MockHttpServletRequest ccNoCache = new MockHttpServletRequest();
		ccNoCache.addHeader("Cache-Control", "no-cache");
		CacheLookupResult bypassRes = cacheService.evaluateCache(request, ccNoCache, null);
		assertThat(bypassRes.status()).isEqualTo(CacheStatus.BYPASS);

		// Singleflight failure fallback to MISS
		SingleFlightManager mockFlight = mock(SingleFlightManager.class);
		when(mockFlight.execute(any(), any())).thenThrow(new RuntimeException("Singleflight boom"));
		AegisCacheService flightFailService = new AegisCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, mockFlight, properties, meterRegistry
		);

		CacheLookupResult failRes = flightFailService.evaluateCache(request, httpReq, "");
		assertThat(failRes.status()).isEqualTo(CacheStatus.MISS);

		// Record savings exception branch
		SimpleMeterRegistry faultyRegistry = new SimpleMeterRegistry() {
			@Override
			protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
				throw new RuntimeException("Registry boom");
			}
		};
		AegisCacheService faultyMeterService = new AegisCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, singleFlightManager, properties, faultyRegistry
		);
		CacheEntry entry = new CacheEntry(
				"id1",
				"t1",
				CacheScope.TENANT,
				"m",
				"p",
				"",
				"",
				"{}",
				1,
				1,
				2,
				Instant.now(),
				1.0f
		);
		when(l0Cache.get(anyString())).thenReturn(entry);
		CacheLookupResult hitFaulty = faultyMeterService.evaluateCache(request, httpReq, "t1");
		assertThat(hitFaulty.isHit()).isTrue();
	}
}
