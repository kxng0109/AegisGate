package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "CacheStatsResponse", description = "Active multi-tier cache telemetry, configuration flags, and similarity thresholds")
public record CacheStatsResponse(
		@Schema(description = "Master cache toggle", example = "true")
		boolean enabled,

		@Schema(description = "Default multi-tenant isolation scope", example = "TENANT")
		String defaultScope,

		@Schema(description = "Cosine similarity threshold for L2 semantic vector matches", example = "0.90")
		double similarityThreshold,

		@Schema(description = "Embedding model alias used for vector caching", example = "text-embedding-3-small")
		String embeddingModel,

		@Schema(description = "Maximum capacity of L0 Caffeine in-memory cache", example = "50000")
		int l0InMemorySize,

		@Schema(description = "Time-to-live for L0 in-memory entries in seconds", example = "60")
		long l0InMemoryTtlSeconds,

		@Schema(description = "Whether L1 distributed Redis exact match cache is active", example = "true")
		boolean l1RedisEnabled,

		@Schema(description = "Whether L2 distributed RediSearch vector cache is active", example = "true")
		boolean l2SemanticEnabled,

		@Schema(description = "Whether polarity and negation intent guard is active", example = "true")
		boolean polarityGuardEnabled,

		@Schema(description = "Whether named entity and number guard is active", example = "true")
		boolean entityGuardEnabled
) {
}
