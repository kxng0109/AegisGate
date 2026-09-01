package io.github.kxng0109.aegisgate.admin.dto;

/**
 * Summary of active cache configurations, thresholds, and operational flags.
 *
 * @param enabled              whether the caching subsystem is enabled
 * @param defaultScope         default multi-tenant isolation scope (TENANT, USER, GLOBAL)
 * @param similarityThreshold  active cosine similarity threshold for L2 semantic hits
 * @param embeddingModel       configured embedding model alias
 * @param l0InMemorySize       maximum number of entries in local L0 cache
 * @param l0InMemoryTtlSeconds local L0 cache entry TTL in seconds
 * @param l1RedisEnabled       whether L1 distributed Redis exact cache is active
 * @param l2SemanticEnabled    whether L2 distributed RediSearch vector cache is active
 * @param polarityGuardEnabled whether polarity/negation guard is active
 * @param entityGuardEnabled   whether entity/number guard is active
 */
public record CacheStatsResponse(
		boolean enabled,
		String defaultScope,
		double similarityThreshold,
		String embeddingModel,
		int l0InMemorySize,
		long l0InMemoryTtlSeconds,
		boolean l1RedisEnabled,
		boolean l2SemanticEnabled,
		boolean polarityGuardEnabled,
		boolean entityGuardEnabled
) {
}
