package io.github.kxng0109.aegisgate.cache.contracts;

/**
 * Status of a cache evaluation operation.
 */
public enum CacheStatus {
	/**
	 * Served from process-local in-memory L0 Caffeine cache (exact match).
	 */
	HIT_L0,

	/**
	 * Served from distributed Redis L1 key-value cache (exact match).
	 */
	HIT_L1,

	/**
	 * Served from distributed RediSearch / Redis VSS L2 vector index (semantic similarity match).
	 */
	HIT_L2,

	/**
	 * Cache miss; request routed to upstream LLM provider.
	 */
	MISS,

	/**
	 * Cache lookup bypassed due to directives or non-deterministic parameters.
	 */
	BYPASS
}
