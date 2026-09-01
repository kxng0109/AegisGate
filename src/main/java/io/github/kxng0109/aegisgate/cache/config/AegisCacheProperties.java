package io.github.kxng0109.aegisgate.cache.config;

import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the AegisGate multi-tier and semantic caching layer.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.cache")
public class AegisCacheProperties {

	/**
	 * Master toggle for the entire caching subsystem.
	 */
	private boolean enabled = true;

	/**
	 * Default multi-tenant isolation scope when not explicitly overridden by client headers.
	 */
	private CacheScope defaultScope = CacheScope.TENANT;

	/**
	 * Default Time-To-Live for cached completions in Redis.
	 */
	private Duration ttl = Duration.ofHours(24);

	/**
	 * Configuration for L0 (In-Memory) and L1 (Redis Distributed) exact caching.
	 */
	private ExactCacheProperties exact = new ExactCacheProperties();

	/**
	 * Configuration for L2 (RediSearch / Redis VSS) vector semantic caching.
	 */
	private SemanticCacheProperties semantic = new SemanticCacheProperties();

	@Getter
	@Setter
	public static class ExactCacheProperties {
		/**
		 * Maximum number of entries kept in the local JVM Caffeine L0 cache.
		 */
		private int l0InMemorySize = 50_000;

		/**
		 * Expiry duration for local L0 in-memory entries.
		 */
		private Duration l0InMemoryTtl = Duration.ofSeconds(60);

		/**
		 * Whether distributed L1 Redis exact hash caching is enabled.
		 */
		private boolean l1RedisEnabled = true;
	}

	@Getter
	@Setter
	public static class SemanticCacheProperties {
		/**
		 * Whether L2 vector semantic caching is enabled.
		 */
		private boolean enabled = true;

		/**
		 * Gateway model alias used to generate embeddings for semantic queries.
		 */
		private String embeddingModel = "text-embedding-3-small";

		/**
		 * Minimum cosine similarity threshold required for an L2 semantic hit (0.00 - 1.00).
		 */
		private double similarityThreshold = 0.90;

		/**
		 * Maximum number of prior turns included in prefix hashing for multi-turn conversations.
		 */
		private int maxTurnCountback = 4;

		/**
		 * Whether to reject semantic hits when negation/polarity keywords mismatch.
		 */
		private boolean polarityGuardEnabled = true;

		/**
		 * Whether to reject semantic hits when named entities, numbers, or currencies mismatch.
		 */
		private boolean entityGuardEnabled = true;

		/**
		 * Temperature floor. Requests with temperature higher than this bypass semantic cache.
		 */
		private double temperatureFloor = 0.1;
	}
}
