package io.github.kxng0109.aegisgate.cache.contracts;

import org.jspecify.annotations.Nullable;

/**
 * Encapsulates the outcome of a multi-tier cache evaluation.
 *
 * @param status           hit tier or miss status
 * @param entry            cached payload entry (null on MISS or BYPASS)
 * @param similarityScore  similarity score (1.0 for exact match, 0.0-1.0 for semantic, 0.0 on miss)
 * @param lookupDurationMs time spent evaluating the cache in milliseconds
 */
public record CacheLookupResult(
		CacheStatus status,
		@Nullable CacheEntry entry,
		float similarityScore,
		long lookupDurationMs
) {
	/**
	 * Convenience factory for a cache miss.
	 *
	 * @param durationMs duration spent in evaluation
	 * @return CacheLookupResult with MISS status
	 */
	public static CacheLookupResult miss(long durationMs) {
		return new CacheLookupResult(CacheStatus.MISS, null, 0.0f, durationMs);
	}

	/**
	 * Convenience factory for a bypassed cache check.
	 *
	 * @return CacheLookupResult with BYPASS status
	 */
	public static CacheLookupResult bypass() {
		return new CacheLookupResult(CacheStatus.BYPASS, null, 0.0f, 0L);
	}

	/**
	 * Convenience factory for a cache hit.
	 *
	 * @param status     hit tier (HIT_L0, HIT_L1, HIT_L2)
	 * @param entry      cached entry
	 * @param score      similarity score
	 * @param durationMs lookup duration in milliseconds
	 * @return CacheLookupResult with hit status
	 */
	public static CacheLookupResult hit(CacheStatus status, CacheEntry entry, float score, long durationMs) {
		return new CacheLookupResult(status, entry, score, durationMs);
	}

	/**
	 * Indicates whether this result represents a valid cache hit.
	 *
	 * @return true if status is HIT_L0, HIT_L1, or HIT_L2 and entry is present
	 */
	public boolean isHit() {
		return entry != null && (status == CacheStatus.HIT_L0 || status == CacheStatus.HIT_L1
				|| status == CacheStatus.HIT_L2);
	}
}
