package io.github.kxng0109.aegisgate.cache.engine.l0;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * L0 Process-Local In-Memory Cache backed by Caffeine for ultra-low-latency (&lt;0.1ms) exact match hits.
 */
@Component
public class InMemoryExactCache {

	private final Cache<String, CacheEntry> cache;

	public InMemoryExactCache(AegisCacheProperties properties) {
		this.cache = Caffeine.newBuilder()
		                     .maximumSize(properties.getExact().getL0InMemorySize())
		                     .expireAfterWrite(properties.getExact().getL0InMemoryTtl())
		                     .build();
	}

	/**
	 * Retrieves an entry from local memory.
	 *
	 * @param exactKey exact compound hash key
	 * @return cached entry or null
	 */
	public @Nullable CacheEntry get(String exactKey) {
		return cache.getIfPresent(exactKey);
	}

	/**
	 * Stores an entry in local memory.
	 *
	 * @param exactKey exact compound hash key
	 * @param entry    cached entry
	 */
	public void put(String exactKey, CacheEntry entry) {
		cache.put(exactKey, entry);
	}

	/**
	 * Invalidates a specific key in local memory.
	 *
	 * @param exactKey exact compound hash key
	 */
	public void invalidate(String exactKey) {
		cache.invalidate(exactKey);
	}

	/**
	 * Evicts all entries from local memory.
	 */
	public void invalidateAll() {
		cache.invalidateAll();
	}
}
