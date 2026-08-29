package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.RateLimitDecision;
import io.github.kxng0109.aegisgate.contracts.RejectionReason;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitEngineTest {

	private static final SHA256Hash HASH = SHA256Hash.fromRawKey("gw-k");

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final DefaultRedisScript<List> script = mock(DefaultRedisScript.class);
	private final RateLimitEngine engine = new RateLimitEngine(redisTemplate, script);

	private static VirtualApiKey key(int rpmLimit, int tpmLimit) {
		return new VirtualApiKey(
				HASH,
				"gw-",
				"owner",
				"name",
				rpmLimit,
				tpmLimit,
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
	}

	private void stubResult(List<?> result) {
		when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenReturn(result);
	}

	@Test
	void allowedDecisionMapsStateAndResetInstants() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L, 0L));
		long before = System.currentTimeMillis();
		RateLimitDecision decision = engine.checkRateLimit(HASH, key(10, 100), 10);
		long after = System.currentTimeMillis();

		RateLimitDecision.Allowed allowed = assertInstanceOf(RateLimitDecision.Allowed.class, decision);
		assertEquals(10, allowed.state().rpmLimit());
		assertEquals(9, allowed.state().rpmRemaining());
		assertEquals(100, allowed.state().tpmLimit());
		assertEquals(90, allowed.state().tpmRemaining());
		assertResetNear(allowed.state().rpmResetAt(), before, after, 60L);
		assertResetNear(allowed.state().tpmResetAt(), before, after, 60L);
	}

	@Test
	void parsesStringTypedLuaResultElements() {
		// StringRedisTemplate deserializes Lua integers as Strings.
		stubResult(List.of("1", "4", "60", "40", "60", "0"));

		RateLimitDecision.Allowed allowed = assertInstanceOf(
				RateLimitDecision.Allowed.class,
				engine.checkRateLimit(HASH, key(5, 50), 10)
		);
		assertEquals(4, allowed.state().rpmRemaining());
		assertEquals(40, allowed.state().tpmRemaining());
	}

	@Test
	void parsesLongTypedLuaResultElements() {
		// Native serializers deserialize Lua integers as Longs.
		stubResult(List.of(1L, 4L, 60L, 40L, 60L, 0L));

		RateLimitDecision.Allowed allowed = assertInstanceOf(
				RateLimitDecision.Allowed.class,
				engine.checkRateLimit(HASH, key(5, 50), 10)
		);
		assertEquals(4, allowed.state().rpmRemaining());
		assertEquals(40, allowed.state().tpmRemaining());
	}

	@Test
	void rpmExceededRejectsWithRpmResetTtl() {
		stubResult(List.of(0L, 0L, 60L, 90L, 60L, 1L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.RPM_EXCEEDED, rejected.reason());
		assertEquals(60, rejected.retryAfterSeconds());
	}

	@Test
	void tpmExceededRejectsWithTpmResetTtl() {
		stubResult(List.of(0L, 9L, 60L, 0L, 60L, 2L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.TPM_EXCEEDED, rejected.reason());
		assertEquals(60, rejected.retryAfterSeconds());
	}

	@Test
	void bothLimitsExceededRpmWins() {
		stubResult(List.of(0L, 0L, 45L, 0L, 30L, 1L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.RPM_EXCEEDED, rejected.reason());
		assertEquals(45, rejected.retryAfterSeconds());
	}

	@Test
	void rejectedZeroWithAllowedZeroFallsBackToTpm() {
		// Defensive path: a corrupted script result with rejected == 0 must still
		// fail closed as TPM_EXCEEDED instead of slipping through as Allowed.
		stubResult(List.of(0L, 0L, 60L, 0L, 60L, 0L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.TPM_EXCEEDED, rejected.reason());
		assertEquals(60, rejected.retryAfterSeconds());
	}

	@Test
	void unlimitedRpmStillEnforcesTpm() {
		stubResult(List.of(0L, 0L, 60L, 0L, 60L, 2L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(0, 100), 10)
		);
		assertEquals(RejectionReason.TPM_EXCEEDED, rejected.reason());
		assertEquals(60, rejected.retryAfterSeconds());
	}

	@Test
	void unlimitedTpmStillEnforcesRpm() {
		stubResult(List.of(0L, 0L, 60L, 0L, 60L, 1L));

		RateLimitDecision.Rejected rejected = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 0), 10)
		);
		assertEquals(RejectionReason.RPM_EXCEEDED, rejected.reason());
		assertEquals(60, rejected.retryAfterSeconds());
	}

	@Test
	void bothUnlimitedAllowsWithZeroRemainingAndWindowReset() {
		stubResult(List.of(1L, 0L, 60L, 0L, 60L, 0L));
		long before = System.currentTimeMillis();
		RateLimitDecision decision = engine.checkRateLimit(HASH, key(0, 0), 10);
		long after = System.currentTimeMillis();

		RateLimitDecision.Allowed allowed = assertInstanceOf(RateLimitDecision.Allowed.class, decision);
		assertEquals(0, allowed.state().rpmLimit());
		assertEquals(0, allowed.state().rpmRemaining());
		assertEquals(0, allowed.state().tpmLimit());
		assertEquals(0, allowed.state().tpmRemaining());
		assertResetNear(allowed.state().rpmResetAt(), before, after, 60L);
		assertResetNear(allowed.state().tpmResetAt(), before, after, 60L);
	}

	@Test
	void clampsZeroEstimatedTokensToMinimum() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L, 0L));
		engine.checkRateLimit(HASH, key(10, 100), 0);
		assertTokensArgument("1");
	}

	@Test
	void clampsNegativeEstimatedTokensToMinimum() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L, 0L));
		engine.checkRateLimit(HASH, key(10, 100), -5);
		assertTokensArgument("1");
	}

	@Test
	void clampsOversizedEstimatedTokensToMaximum() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L, 0L));
		engine.checkRateLimit(HASH, key(10, 100), 5_000_000);
		assertTokensArgument("1000000");
	}

	@Test
	void passesExactKeysAndWindowMillisArgument() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L, 0L));
		engine.checkRateLimit(HASH, key(10, 100), 10);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(eq(script), keysCaptor.capture(), argsCaptor.capture());

		assertEquals(
				List.of("ratelimit:rpm:" + HASH.hex(), "ratelimit:tpm:" + HASH.hex()),
				keysCaptor.getValue()
		);
		Object[] args = argsCaptor.getValue();
		assertEquals(4, args.length);
		assertEquals("10", args[0]);
		assertEquals("10", args[1]);
		assertEquals("100", args[2]);
		assertEquals(String.valueOf(RateLimitEngine.WINDOW_MILLIS), args[3]);
	}

	@Test
	void nullResultThrowsUnavailable() {
		stubResult(null);

		RateLimitUnavailableException ex = assertThrows(
				RateLimitUnavailableException.class,
				() -> engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals("Unexpected rate-limit result from Redis: null", ex.getMessage());
	}

	@Test
	void undersizedResultThrowsUnavailable() {
		stubResult(List.of(1L, 9L, 60L, 90L, 60L));

		RateLimitUnavailableException ex = assertThrows(
				RateLimitUnavailableException.class,
				() -> engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals("Unexpected rate-limit result from Redis: 5", ex.getMessage());
	}

	@Test
	void dataAccessExceptionWrappedAsUnavailable() {
		RedisConnectionFailureException cause = new RedisConnectionFailureException("redis down");
		when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenThrow(cause);

		RateLimitUnavailableException ex = assertThrows(
				RateLimitUnavailableException.class,
				() -> engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals("Redis rate-limit backend unavailable", ex.getMessage());
		assertSame(cause, ex.getCause());
	}

	@Test
	void poolExceptionWrappedAsUnavailable() {
		PoolException cause = new PoolException("connection pool exhausted");
		when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenThrow(cause);

		RateLimitUnavailableException ex = assertThrows(
				RateLimitUnavailableException.class,
				() -> engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals("Redis rate-limit backend unavailable", ex.getMessage());
		assertSame(cause, ex.getCause());
	}

	@Test
	void retryAfterIsAtLeastOneSecondWhenResetTtlIsZero() {
		when(redisTemplate.execute(eq(script), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 0L, 0L, 0L, 0L, 1L))
				.thenReturn(List.of(0L, 0L, 0L, 0L, 0L, 2L));

		RateLimitDecision.Rejected rpm = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.RPM_EXCEEDED, rpm.reason());
		assertEquals(1, rpm.retryAfterSeconds());

		RateLimitDecision.Rejected tpm = assertInstanceOf(
				RateLimitDecision.Rejected.class,
				engine.checkRateLimit(HASH, key(10, 100), 10)
		);
		assertEquals(RejectionReason.TPM_EXCEEDED, tpm.reason());
		assertEquals(1, tpm.retryAfterSeconds());
	}

	@Test
	void negativeRemainingIsClampedToZeroDefensively() {
		// The script clamps remaining budgets to >= 0, so a negative value here is
		// impossible through the contract; the engine still normalizes it to 0.
		stubResult(List.of(1L, -3L, 60L, -20L, 60L, 0L));

		RateLimitDecision.Allowed allowed = assertInstanceOf(
				RateLimitDecision.Allowed.class,
				engine.checkRateLimit(HASH, key(5, 50), 10)
		);
		assertEquals(0, allowed.state().rpmRemaining());
		assertEquals(0, allowed.state().tpmRemaining());
	}

	@Test
	void oversizedRemainingIsClampedToIntegerMax() {
		stubResult(List.of(1L, 2_147_483_648L, 60L, 40L, 60L, 0L));

		RateLimitDecision.Allowed allowed = assertInstanceOf(
				RateLimitDecision.Allowed.class,
				engine.checkRateLimit(HASH, key(5, 50), 10)
		);
		assertEquals(Integer.MAX_VALUE, allowed.state().rpmRemaining());
		assertEquals(40, allowed.state().tpmRemaining());
	}

	@Test
	void luaScriptResourceContainsRequiredPrimitives() throws IOException {
		ClassPathResource resource = new ClassPathResource("rate_limit.lua");
		assertTrue(resource.exists(), "rate_limit.lua must exist on the classpath");
		String source;
		try (InputStream in = resource.getInputStream()) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertFalse(source.isBlank(), "script source must not be empty");
		assertTrue(source.contains("INCR"), "script must INCR the RPM counter");
		assertTrue(source.contains("INCRBY"), "script must INCRBY the TPM counter");
		assertTrue(source.contains("EXPIRE"), "script must EXPIRE counter keys");
		assertTrue(source.contains("return {"), "script must return the result table");
	}

	/**
	 * Asserts a reset instant equals {@code [beforeMillis, afterMillis] +
	 * ttlSeconds} within a one-second tolerance, keeping the assertion immune to
	 * wall-clock jitter between the captured instants.
	 */
	private static void assertResetNear(Instant reset, long beforeMillis, long afterMillis, long ttlSeconds) {
		long resetMillis = reset.toEpochMilli();
		assertTrue(resetMillis >= beforeMillis + (ttlSeconds - 1) * 1000L, "reset instant too early");
		assertTrue(resetMillis <= afterMillis + (ttlSeconds + 1) * 1000L, "reset instant too late");
	}

	private void assertTokensArgument(String expectedTokens) {
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(eq(script), anyList(), argsCaptor.capture());
		assertEquals(expectedTokens, argsCaptor.getValue()[1]);
	}
}