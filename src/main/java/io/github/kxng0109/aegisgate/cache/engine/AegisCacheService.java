package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.*;
import io.github.kxng0109.aegisgate.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * High-performance facade coordinating multi-tier (L0 in-memory -> L1 Redis exact -> L2 Redis vector) cache lookups,
 * single-flight stampede protection, asynchronous ingestion, and metrics collection.
 */
@Slf4j
@Service
public class AegisCacheService {

	private final InMemoryExactCache l0Cache;
	private final RedisExactCache l1Cache;
	private final RedisSemanticVectorCache l2Cache;
	private final CacheKeyGenerator keyGenerator;
	private final CachePolicyEngine policyEngine;
	private final SingleFlightManager singleFlightManager;
	private final AegisCacheProperties properties;
	private final MeterRegistry meterRegistry;

	public AegisCacheService(
			InMemoryExactCache l0Cache,
			RedisExactCache l1Cache,
			RedisSemanticVectorCache l2Cache,
			CacheKeyGenerator keyGenerator,
			CachePolicyEngine policyEngine,
			SingleFlightManager singleFlightManager,
			AegisCacheProperties properties,
			@Nullable MeterRegistry meterRegistry
	) {
		this.l0Cache = l0Cache;
		this.l1Cache = l1Cache;
		this.l2Cache = l2Cache;
		this.keyGenerator = keyGenerator;
		this.policyEngine = policyEngine;
		this.singleFlightManager = singleFlightManager;
		this.properties = properties;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	@PostConstruct
	void init() {
		if (properties.isEnabled() && properties.getSemantic().isEnabled()) {
			l2Cache.initializeIndex();
		}
	}

	/**
	 * Evaluates whether a valid cached completion exists across L0, L1, and L2 tiers.
	 *
	 * @param request     parsed OpenAI chat completion request
	 * @param httpRequest servlet HTTP request with headers and auth attributes
	 * @param ownerId     tenant identifier
	 * @return CacheLookupResult indicating hit tier or miss
	 */
	public CacheLookupResult evaluateCache(
			OpenAiChatRequest request,
			HttpServletRequest httpRequest,
			@Nullable String ownerId
	) {
		if (!policyEngine.shouldEvaluateCache(request, httpRequest)) {
			recordMetric(CacheStatus.BYPASS, request.model(), ownerId);
			return CacheLookupResult.bypass();
		}

		String effectiveOwner = (ownerId == null || ownerId.isBlank()) ? "unknown" : ownerId;
		CacheScope scope = policyEngine.resolveScope(httpRequest);
		String userId = httpRequest.getHeader("X-User-Id");
		CompoundCacheKey key = keyGenerator.generateKey(
				request,
				effectiveOwner,
				scope,
				userId,
				properties.getSemantic().getMaxTurnCountback()
		);

		long start = System.currentTimeMillis();
		try {
			return singleFlightManager.execute(key.exactHash(), () -> doLookup(key, start));
		} catch (Exception ex) {
			log.warn("Cache evaluation failed non-fatally: {}", ex.getMessage());
			long duration = System.currentTimeMillis() - start;
			recordMetric(CacheStatus.MISS, key.model(), key.ownerId());
			return CacheLookupResult.miss(duration);
		}
	}

	private CacheLookupResult doLookup(CompoundCacheKey key, long start) {
		// 1. Tier L0: In-Memory Caffeine exact match (<0.1ms)
		CacheEntry l0Hit = l0Cache.get(key.exactHash());
		if (l0Hit != null) {
			long duration = System.currentTimeMillis() - start;
			recordMetric(CacheStatus.HIT_L0, key.model(), key.ownerId());
			recordSavings(l0Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L0, l0Hit, 1.0f, duration);
		}

		// 2. Tier L1: Distributed Redis exact match (1-2ms)
		CacheEntry l1Hit = l1Cache.get(key);
		if (l1Hit != null) {
			long duration = System.currentTimeMillis() - start;
			l0Cache.put(key.exactHash(), l1Hit);
			recordMetric(CacheStatus.HIT_L1, key.model(), key.ownerId());
			recordSavings(l1Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L1, l1Hit, 1.0f, duration);
		}

		// 3. Tier L2: Distributed RediSearch Vector Similarity Search (10-25ms)
		CacheEntry l2Hit = l2Cache.findSemanticMatch(key);
		if (l2Hit != null) {
			long duration = System.currentTimeMillis() - start;
			l0Cache.put(key.exactHash(), l2Hit);
			recordMetric(CacheStatus.HIT_L2, key.model(), key.ownerId());
			recordSavings(l2Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L2, l2Hit, l2Hit.similarityScore(), duration);
		}

		long duration = System.currentTimeMillis() - start;
		recordMetric(CacheStatus.MISS, key.model(), key.ownerId());
		return CacheLookupResult.miss(duration);
	}

	/**
	 * Stores a completed response into L0 in-memory, L1 Redis exact, and L2 Redis semantic vector cache.
	 *
	 * @param request          original chat request
	 * @param httpRequest      servlet HTTP request
	 * @param ownerId          tenant identifier
	 * @param responseJson     completion JSON payload
	 * @param promptTokens     prompt token count
	 * @param completionTokens completion token count
	 */
	public void storeResponse(
			OpenAiChatRequest request,
			HttpServletRequest httpRequest,
			@Nullable String ownerId,
			String responseJson,
			int promptTokens,
			int completionTokens
	) {
		if (!policyEngine.shouldStoreInCache(request, httpRequest)) {
			return;
		}

		String effectiveOwner = (ownerId == null || ownerId.isBlank()) ? "unknown" : ownerId;
		CacheScope scope = policyEngine.resolveScope(httpRequest);
		String userId = httpRequest.getHeader("X-User-Id");
		CompoundCacheKey key = keyGenerator.generateKey(
				request,
				effectiveOwner,
				scope,
				userId,
				properties.getSemantic().getMaxTurnCountback()
		);

		String entryId = UUID.randomUUID().toString();
		Duration ttl = properties.getTtl();
		CacheEntry entry = new CacheEntry(
				entryId,
				key.ownerId(),
				key.scope(),
				key.model(),
				key.promptText(),
				key.systemPromptHash(),
				key.prefixHash(),
				responseJson,
				promptTokens,
				completionTokens,
				promptTokens + completionTokens,
				Instant.now(),
				1.0f
		);

		// Store into L0 in-memory
		l0Cache.put(key.exactHash(), entry);

		// Store into L1 Redis exact match
		l1Cache.put(key, entry, ttl);

		// Store into L2 RediSearch vector index
		l2Cache.storeSemanticEntry(
				key,
				responseJson,
				promptTokens,
				completionTokens,
				promptTokens + completionTokens,
				ttl
		);
	}

	/**
	 * Invalidates all local in-memory L0 cache entries.
	 */
	public void purgeLocalCache() {
		l0Cache.invalidateAll();
	}

	private void recordMetric(CacheStatus status, String model, @Nullable String ownerId) {
		try {
			Counter.builder("aegisgate.cache.requests")
			       .tag("status", status.name().toLowerCase())
			       .tag("model", model != null ? model : "unknown")
			       .tag("tenant", ownerId != null ? ownerId : "unknown")
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}

	private void recordSavings(CacheEntry entry) {
		try {
			Counter.builder("aegisgate.cache.tokens.saved")
			       .tag("model", entry.model())
			       .tag("tenant", entry.ownerId())
			       .register(meterRegistry)
			       .increment(entry.totalTokens());
		} catch (Exception ignored) {
		}
	}
}
