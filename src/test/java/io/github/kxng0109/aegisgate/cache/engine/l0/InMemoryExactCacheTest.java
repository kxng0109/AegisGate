package io.github.kxng0109.aegisgate.cache.engine.l0;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryExactCache")
class InMemoryExactCacheTest {

	@Test
	@DisplayName("get, put, invalidate, and invalidateAll operate properly on Caffeine L0 cache")
	void cacheOperations() {
		AegisCacheProperties props = new AegisCacheProperties();
		InMemoryExactCache cache = new InMemoryExactCache(props);

		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"prompt",
				"sys",
				"prefix",
				"{}",
				10,
				20,
				30,
				Instant.now(),
				1.0f
		);

		cache.put("key1", entry);
		assertThat(cache.get("key1")).isEqualTo(entry);
		assertThat(cache.get("missing")).isNull();

		cache.invalidate("key1");
		assertThat(cache.get("key1")).isNull();

		cache.put("key2", entry);
		cache.put("key3", entry);
		cache.invalidateAll();
		assertThat(cache.get("key2")).isNull();
		assertThat(cache.get("key3")).isNull();
	}
}
