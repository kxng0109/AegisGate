package io.github.kxng0109.aegisgate.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the replay service with stubbed Redis and repository backends: hit/miss/mismatch/in-flight
 * lookup, fill-claim races, oversized skip, and tier-failure degradation (re-proxy, never wrong answers).
 */
@DisplayName("ReplayService")
class ReplayServiceTest {

	@SuppressWarnings("unchecked")
	private record Harness(StringRedisTemplate template, HashOperations<String, Object, Object> hashOps,
	                       ValueOperations<String, String> valueOps, ReplayRepository repository,
	                       ReplayService service) {
	}

	@SuppressWarnings("unchecked")
	private static Harness harness() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(template.opsForHash()).thenReturn(hashOps);
		when(template.opsForValue()).thenReturn(valueOps);
		ReplayRepository repository = mock(ReplayRepository.class);
		ReplayService service = new ReplayService(template, repository, new SimpleMeterRegistry());
		return new Harness(template, hashOps, valueOps, repository, service);
	}

	@Test
	@DisplayName("lookup serves a hit when the fingerprint matches")
	void lookupServesHit() {
		Harness harness = harness();
		Map<Object, Object> fields = new HashMap<>();
		fields.put("body", "{\"ok\":true}");
		fields.put("body_hash", "abc123");
		fields.put("sse", "0");
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(fields);

		ReplayService.Lookup lookup = harness.service().lookup("rk-1", "abc123");

		assertThat(lookup).isInstanceOf(ReplayService.Hit.class);
		ReplayService.Hit hit = (ReplayService.Hit) lookup;
		assertThat(new String(hit.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
		assertThat(hit.sseFramed()).isFalse();
	}

	@Test
	@DisplayName("lookup reports mismatch on a reused key with another body")
	void lookupReportsMismatch() {
		Harness harness = harness();
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1"))
				.thenReturn(Map.<Object, Object>of("body", "{}", "body_hash", "other"));

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.FingerprintMismatch.class);
	}

	@Test
	@DisplayName("lookup reports in-flight when only the fill claim exists")
	void lookupReportsInFlight() {
		Harness harness = harness();
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(Map.of());
		when(harness.template().hasKey(ReplayService.FILL_PREFIX + "rk-1")).thenReturn(true);

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.InFlight.class);
	}

	@Test
	@DisplayName("lookup misses on empty store without a fill claim")
	void lookupMissesCleanly() {
		Harness harness = harness();
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(Map.of());
		when(harness.template().hasKey(ReplayService.FILL_PREFIX + "rk-1")).thenReturn(false);

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.Miss.class);
	}

	@Test
	@DisplayName("lookup degrades to miss when the cache tier is down")
	void lookupDegradesOnOutage() {
		Harness harness = harness();
		when(harness.hashOps().entries(anyString())).thenThrow(new RuntimeException("redis down"));

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.Miss.class);
	}

	@Test
	@DisplayName("beginFill claims once; losers back off")
	void beginFillClaimsOnce() {
		Harness harness = harness();
		when(harness.valueOps().setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.thenReturn(true, false);

		assertThat(harness.service().beginFill("rk-1")).isTrue();
		assertThat(harness.service().beginFill("rk-1")).isFalse();
	}

	@Test
	@DisplayName("beginFill allows the flight when the cache tier is down")
	void beginFillAllowsOnOutage() {
		Harness harness = harness();
		when(harness.valueOps().setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.thenThrow(new RuntimeException("redis down"));

		assertThat(harness.service().beginFill("rk-1")).isTrue();
	}

	@Test
	@DisplayName("store writes both tiers and releases the fill")
	void storeWritesBothTiers() {
		Harness harness = harness();
		byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

		assertThat(harness.service().store("rk-1", "abc123", body, false)).isTrue();

		verify(harness.template()).expire(ReplayService.PREFIX + "rk-1", ReplayService.HOT_TTL);
		verify(harness.repository()).save(any(ReplayRecord.class));
		verify(harness.template()).delete(ReplayService.FILL_PREFIX + "rk-1");
	}

	@Test
	@DisplayName("store skips oversized payloads without truncating")
	void storeSkipsOversized() {
		Harness harness = harness();
		byte[] body = new byte[ReplayService.MAX_BODY_BYTES + 1];

		assertThat(harness.service().store("rk-1", "abc123", body, false)).isFalse();

		verify(harness.repository(), never()).save(any());
		verify(harness.template()).delete(ReplayService.FILL_PREFIX + "rk-1");
	}

	@Test
	@DisplayName("store survives tier outages without failing the response")
	void storeSurvivesOutages() {
		Harness harness = harness();
		doThrow(new RuntimeException("redis down")).when(harness.hashOps()).putAll(anyString(), any(Map.class));
		when(harness.repository().save(any())).thenThrow(new RuntimeException("db down"));
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

		assertThat(harness.service().store("rk-1", "abc123", body, true)).isTrue();
		verify(harness.template()).delete(ReplayService.FILL_PREFIX + "rk-1");
	}

	@Test
	@DisplayName("releaseFill absorbs cache outages")
	void releaseFillAbsorbsOutage() {
		Harness harness = harness();
		doThrow(new RuntimeException("redis down")).when(harness.template()).delete(anyString());

		harness.service().releaseFill("rk-1");
	}

	@Test
	@DisplayName("null meter registry falls back to an isolated registry")
	void nullRegistryFallsBack() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		ReplayService service = new ReplayService(template, mock(ReplayRepository.class), null);

		assertThat(service.lookup("rk-1", "abc123")).isInstanceOf(ReplayService.Miss.class);
	}

	@Test
	@DisplayName("null entries degrade to miss")
	void nullEntriesDegradeToMiss() {
		Harness harness = harness();
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(null);
		when(harness.template().hasKey(ReplayService.FILL_PREFIX + "rk-1")).thenReturn(false);

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.Miss.class);
	}

	@Test
	@DisplayName("empty stored body degrades to miss")
	void emptyBodyDegradesToMiss() {
		Harness harness = harness();
		Map<Object, Object> fields = new HashMap<>();
		fields.put("body", "");
		fields.put("body_hash", "abc123");
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(fields);

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.Miss.class);
	}

	@Test
	@DisplayName("null field values degrade safely")
	void nullFieldsDegradeSafely() {
		Harness harness = harness();
		Map<Object, Object> fields = new HashMap<>();
		fields.put("body", null);
		fields.put("body_hash", null);
		when(harness.hashOps().entries(ReplayService.PREFIX + "rk-1")).thenReturn(fields);

		assertThat(harness.service().lookup("rk-1", "abc123"))
				.isInstanceOf(ReplayService.FingerprintMismatch.class);
	}

	@Test
	@DisplayName("broken meter registry never breaks lookups")
	void brokenRegistryNeverBreaks() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
		when(template.opsForHash()).thenReturn(hashOps);
		Map<Object, Object> fields = new HashMap<>();
		fields.put("body", "{}");
		fields.put("body_hash", "abc123");
		when(hashOps.entries(anyString())).thenReturn(fields);
		ReplayService service = new ReplayService(template, mock(ReplayRepository.class),
				mock(MeterRegistry.class));

		assertThat(service.lookup("rk-1", "abc123")).isInstanceOf(ReplayService.Hit.class);
	}
}
