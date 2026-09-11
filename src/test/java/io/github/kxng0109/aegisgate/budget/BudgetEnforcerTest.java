package io.github.kxng0109.aegisgate.budget;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the spend-budget enforcer with a stubbed script backend.
 */
@DisplayName("BudgetEnforcer")
class BudgetEnforcerTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	private final DefaultRedisScript<List> script = script();

	private final CostCalculator costCalculator = mock(CostCalculator.class);

	private final BudgetEnforcer enforcer =
			new BudgetEnforcer(redisTemplate, script, costCalculator, new SimpleMeterRegistry());

	private static SHA256Hash keyHash() {
		return SHA256Hash.fromRawKey("gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	@SuppressWarnings("unchecked")
	private static DefaultRedisScript<List> script() {
		return new DefaultRedisScript<>("return {1,0,-1,0,0}", List.class);
	}

	@Test
	@DisplayName("allowed decisions pass remaining spend through")
	void allowedPassesThrough() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 2L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Allowed allowed
				           && allowed.remainingMicros() == 500L
				           && allowed.resetSeconds() == 60L);
	}

	@Test
	@DisplayName("denied decisions map level, window, and retry horizon")
	void deniedMapsLevelWindowAndRetry() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 3L, 0L, 45L, 2L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "TEAM".equals(denied.level())
				           && "MINUTE".equals(denied.window())
				           && denied.retryAfterSeconds() == 45L);
	}

	@Test
	@DisplayName("monthly denies resolve month-end horizons")
	void monthlyDeniesResolveMonthEnd() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 4L, 0L, 0L, 1L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "TEAM".equals(denied.level())
				           && "MONTH".equals(denied.window())
				           && denied.retryAfterSeconds() >= 1L
				           && denied.retryAfterSeconds() <= 2_678_400L);
	}

	@Test
	@DisplayName("string wire values are accepted like integers")
	void stringWireValuesAccepted() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of("1", "0", "500", "60", "2"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("malformed script results fail closed")
	void malformedShapeFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(List.of(1L));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10)
		);
	}

	@Test
	@DisplayName("Redis failures fail closed for every key class")
	void redisDownFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10)
		);
	}

	@Test
	@DisplayName("confirmed-unbudgeted keys skip the second round trip")
	void presenceSkipsSecondRoundTrip() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, -1L, 0L, 0L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);
		enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10);

		verify(redisTemplate, times(1)).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("prompt token heuristic is documented and bounded")
	void estimatePromptTokensIsBounded() {
		assertEquals(1, BudgetEnforcer.estimatePromptTokens(0));
		assertEquals(1, BudgetEnforcer.estimatePromptTokens(4));
		assertEquals(2, BudgetEnforcer.estimatePromptTokens(5));
		assertEquals(25, BudgetEnforcer.estimatePromptTokens(100));
	}

	@Test
	@DisplayName("key builders follow the documented layout")
	void keyBuildersFollowLayout() {
		assertEquals("budget:cfg:TEAM:tenant-a", BudgetEnforcer.cfgKey("TEAM", "tenant-a"));
		assertTrue(BudgetEnforcer.minuteKey("KEY", "ab12", 42L).equals("budget:KEY:ab12:minute:42"));
		assertTrue(BudgetEnforcer.monthKey("ORG", "global", "2026-09").equals("budget:ORG:global:month:2026-09"));
		assertTrue(BudgetEnforcer.secondsToMonthEnd() >= 1L);
	}

	@Test
	@DisplayName("null meter registry falls back to an isolated registry")
	void nullRegistryFallsBack() {
		BudgetEnforcer local =
				new BudgetEnforcer(redisTemplate, script, costCalculator, null);
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 2L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = local.checkBudget(
				SHA256Hash.fromRawKey("gw-cccccccccccccccccccccccccccccccc"), "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("null script result fails closed")
	void nullScriptResultFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(null);

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(
								SHA256Hash.fromRawKey("gw-dddddddddddddddddddddddddddddddd"), "owner-1",
								ProviderType.OPENAI, "gpt-5.6-luna", 10)
		);
	}

	@Test
	@DisplayName("non-numeric wire values fail closed")
	void nonNumericWireValueFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, Boolean.TRUE, 60L, 2L));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(
								SHA256Hash.fromRawKey("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"), "owner-1",
								ProviderType.OPENAI, "gpt-5.6-luna", 10)
		);
	}

	@Test
	@DisplayName("null owner skips the TEAM level keys")
	@SuppressWarnings("unchecked")
	void nullOwnerSkipsTeamLevel() {
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 1L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-ffffffffffffffffffffffffffffffff"), null,
				ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Allowed);
		verify(redisTemplate, atLeastOnce()).execute(any(), keysCaptor.capture(), any(Object[].class));
		List<String> keys = keysCaptor.getValue();
		assertEquals("", keys.get(1));
		assertEquals("", keys.get(4));
		assertEquals("", keys.get(7));
	}

	@Test
	@DisplayName("blank owner skips the TEAM level keys")
	void blankOwnerSkipsTeamLevel() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 1L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-11111111111111111111111111111111"), "  ",
				ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("org-level denies map level and minute horizon")
	void orgDenialMapsLevel() {
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 5L, 0L, 60L, 3L));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-22222222222222222222222222222222"), "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "ORG".equals(denied.level())
				           && "MINUTE".equals(denied.window())
				           && denied.retryAfterSeconds() == 60L);
	}
}
