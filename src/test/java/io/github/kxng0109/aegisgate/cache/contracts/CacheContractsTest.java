package io.github.kxng0109.aegisgate.cache.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheContracts")
class CacheContractsTest {

	@Test
	@DisplayName("CacheEntry withSimilarityScore creates an updated copy")
	void cacheEntryWithSimilarityScore() {
		Instant now = Instant.now();
		CacheEntry entry = new CacheEntry(
				"id1", "tenant1", CacheScope.TENANT, "gpt-4o", "prompt", "sys", "prefix", "{}", 10, 20, 30, now, 1.0f
		);

		CacheEntry updated = entry.withSimilarityScore(0.92f);
		assertThat(updated.similarityScore()).isEqualTo(0.92f);
		assertThat(updated.id()).isEqualTo("id1");
		assertThat(updated.promptText()).isEqualTo("prompt");
	}

	@Test
	@DisplayName("CacheLookupResult factories and isHit helper")
	void cacheLookupResult() {
		CacheLookupResult miss = CacheLookupResult.miss(10L);
		assertThat(miss.isHit()).isFalse();
		assertThat(miss.status()).isEqualTo(CacheStatus.MISS);

		CacheLookupResult bypass = CacheLookupResult.bypass();
		assertThat(bypass.isHit()).isFalse();
		assertThat(bypass.status()).isEqualTo(CacheStatus.BYPASS);

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
		CacheLookupResult hitL0 = CacheLookupResult.hit(CacheStatus.HIT_L0, entry, 1.0f, 5L);
		assertThat(hitL0.isHit()).isTrue();

		CacheLookupResult hitL1 = CacheLookupResult.hit(CacheStatus.HIT_L1, entry, 1.0f, 5L);
		assertThat(hitL1.isHit()).isTrue();

		CacheLookupResult hitL2 = CacheLookupResult.hit(CacheStatus.HIT_L2, entry, 0.95f, 5L);
		assertThat(hitL2.isHit()).isTrue();

		CacheLookupResult hitNull = new CacheLookupResult(CacheStatus.HIT_L0, null, 1.0f, 1L);
		assertThat(hitNull.isHit()).isFalse();

		CacheLookupResult missWithEntry = new CacheLookupResult(CacheStatus.MISS, entry, 0.0f, 1L);
		assertThat(missWithEntry.isHit()).isFalse();
	}
}
