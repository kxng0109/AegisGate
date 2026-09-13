package io.github.kxng0109.aegisgate.budget;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the settlement surface of {@link BudgetEnforcer} with a stubbed Redis backend: hold-cost math
 * (including overflow fail-closed), hold creation wire forms, settle owner levels and failure modes, scan/read
 * nullability, and re-arm. Lua semantics themselves are proven against real Redis in
 * {@link SettleLuaIntegrationTest}.
 */
@DisplayName("BudgetEnforcer settlement")
class BudgetEnforcerSettlementTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	private final CostCalculator costCalculator = mock(CostCalculator.class);

	@SuppressWarnings("unchecked")
	private static DefaultRedisScript<List> script() {
		return new DefaultRedisScript<>("return {1,0,-1,0,0}", List.class);
	}

	private BudgetEnforcer enforcer() {
		return new BudgetEnforcer(redisTemplate, script(), script(), script(), costCalculator,
				new SimpleMeterRegistry());
	}

	private static SHA256Hash keyHash() {
		return SHA256Hash.fromRawKey("gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	@Test
	@DisplayName("hold cost prices prompt and output at their own rates")
	void holdCostAddsBothSides() {
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong()))
				.thenAnswer(inv -> (Long) inv.getArgument(2) * 2L + (Long) inv.getArgument(3) * 5L);
		when(redisTemplate.execute(any(), anyList(), any(), any()))
				.thenReturn(List.of(1L, 0L, 100L, 60L, 2L));

		enforcer().authorizeHold(keyHash(), "owner-1", ProviderType.OPENAI, "m", 10, 20, null);

		// H = 10*2 (prompt) + 20*5 (output) = 120, charged through the same gate.
		verify(redisTemplate).execute(any(), anyList(), eq("120"), any());
	}

	@Test
	@DisplayName("hold admission sends H through the atomic gate")
	void authorizeHoldSendsHoldCost() {
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong()))
				.thenAnswer(inv -> (Long) inv.getArgument(2) + (Long) inv.getArgument(3));
		when(redisTemplate.execute(any(), anyList(), any(), any()))
				.thenReturn(List.of(1L, 0L, 100L, 60L, 2L));

		BudgetEnforcer.HoldAuthorization auth =
				enforcer().authorizeHold(keyHash(), "owner-1", ProviderType.OPENAI, "m", 10, 20, null);

		assertThat(auth.decision()).isInstanceOf(BudgetDecision.Allowed.class);
		assertThat(auth.holdMicros()).isEqualTo(30L);
		assertThat(auth.holdMonth()).isNotBlank();
	}

	@Test
	@DisplayName("hold pricing failure fails closed")
	void holdPricingFailureFailsClosed() {
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong()))
				.thenThrow(new RuntimeException("catalog down"));

		assertThatThrownBy(() ->
				enforcer().authorizeHold(keyHash(), "owner-1", ProviderType.OPENAI, "m", 10, 20, null))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("hold cost overflow fails closed instead of wrapping")
	void holdCostOverflowFailsClosed() {
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(Long.MAX_VALUE);

		assertThatThrownBy(() ->
				enforcer().authorizeHold(keyHash(), null, ProviderType.OPENAI, "m", 10, 20, null))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("authorizeHold short-circuits known-unbudgeted keys without pricing")
	void authorizeHoldSkipsKnownUnbudgeted() {
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(4_000L);
		when(redisTemplate.execute(any(), anyList(), any(), any()))
				.thenReturn(List.of(1L, 0L, -1L, 0L, 0L));
		BudgetEnforcer enforcer = enforcer();
		enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "m", 10, null);

		BudgetEnforcer.HoldAuthorization auth =
				enforcer.authorizeHold(keyHash(), "owner-1", ProviderType.OPENAI, "m", 10, 20, null);

		assertThat(auth.decision()).isInstanceOf(BudgetDecision.Allowed.class);
		assertThat(auth.holdMicros()).isZero();
	}

	@Test
	@DisplayName("createHold reports creation and duplicates")
	void createHoldReportsDuplicates() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 3600L), List.of(0L, 3599L));
		BudgetEnforcer enforcer = enforcer();

		assertThat(enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L)).isTrue();
		assertThat(enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L)).isFalse();
	}

	@Test
	@DisplayName("createHold accepts the String wire form")
	void createHoldAcceptsStringWire() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of("1", "60"));
		BudgetEnforcer enforcer = enforcer();

		assertThat(enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L)).isTrue();
	}

	@Test
	@DisplayName("createHold rejects malformed shapes fail-closed")
	void createHoldRejectsMalformed() {
		BudgetEnforcer enforcer = enforcer();
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(null);
		assertThatThrownBy(() ->
				enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L))
				.isInstanceOf(RateLimitUnavailableException.class);

		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 2L, 3L));
		assertThatThrownBy(() ->
				enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L))
				.isInstanceOf(RateLimitUnavailableException.class);

		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of("1", "not-a-number"));
		assertThatThrownBy(() ->
				enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L))
				.isInstanceOf(RateLimitUnavailableException.class);

		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, new Object()));
		assertThatThrownBy(() ->
				enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L))
				.isInstanceOf(RateLimitUnavailableException.class);

		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
				.thenThrow(new RuntimeException("redis down"));
		assertThatThrownBy(() ->
				enforcer.createHold("h1", "KEY:hex", 100L, "2026-09", 1_700_000_000L, 3600L))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("settle threads owner TEAM keys and parses the outcome")
	void settleThreadsOwnerLevels() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 4_000L, 10L, 2L));

		BudgetEnforcer.SettleOutcome outcome = enforcer()
				.settle("hold-1", "KEY", "hex", "owner-1", "hex", "2026-09", "2026-09", 4_000L, 0L);

		assertThat(outcome.settled()).isTrue();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_OK);
		assertThat(outcome.amountApplied()).isEqualTo(4_000L);
		assertThat(outcome.remainingMonthly()).isEqualTo(10L);
		assertThat(outcome.gapSet()).isFalse();
	}

	@Test
	@DisplayName("settle without owner skips TEAM keys and fails closed on outage")
	void settleSkipsTeamKeysAndFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenThrow(new RuntimeException("redis down"));

		assertThatThrownBy(() -> enforcer()
				.settle("hold-1", "KEY", "hex", null, "hex", "2026-09", "2026-09", 4_000L, 0L))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("dueHoldKeys returns the scan result, empty on null, fail-closed on outage")
	@SuppressWarnings("unchecked")
	void dueHoldKeysHandlesScanOutcomes() {
		ZSetOperations<String, String> zops = mock(ZSetOperations.class);
		when(redisTemplate.opsForZSet()).thenReturn(zops);
		AtomicInteger calls = new AtomicInteger();
		when(zops.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
				.thenAnswer(inv -> calls.getAndIncrement() == 0 ? Set.of("k1") : null);

		assertThat(enforcer().dueHoldKeys(500, 1_700_000_000L)).containsExactly("k1");
		assertThat(enforcer().dueHoldKeys(500, 1_700_000_000L)).isEmpty();

		when(zops.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
				.thenThrow(new RuntimeException("redis down"));
		assertThatThrownBy(() -> enforcer().dueHoldKeys(500, 1_700_000_000L))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("rearmHold propagates outage fail-closed")
	void rearmHoldFailsClosed() {
		ZSetOperations<String, String> zops = mock(ZSetOperations.class);
		when(redisTemplate.opsForZSet()).thenReturn(zops);
		when(zops.add(anyString(), anyString(), anyDouble())).thenThrow(new RuntimeException("redis down"));

		assertThatThrownBy(() -> enforcer().rearmHold("k1", 1_700_000_000L))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("readHold returns entries, empty on null, fail-closed on outage")
	@SuppressWarnings("unchecked")
	void readHoldHandlesOutcomes() {
		HashOperations<String, Object, Object> hops = mock(HashOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hops);
		AtomicInteger calls = new AtomicInteger();
		when(hops.entries(anyString()))
				.thenAnswer(inv -> calls.getAndIncrement() == 0 ? Map.<Object, Object>of("state", "HOLD") : null);

		assertThat(enforcer().readHold("k1")).containsEntry("state", "HOLD");
		assertThat(enforcer().readHold("k1")).isEmpty();

		when(hops.entries(anyString())).thenThrow(new RuntimeException("redis down"));
		assertThatThrownBy(() -> enforcer().readHold("k1"))
				.isInstanceOf(RateLimitUnavailableException.class);
	}

	@Test
	@DisplayName("presence invalidation and marking stay side-effect safe")
	void presenceMaintenanceIsSafe() {
		BudgetEnforcer enforcer = enforcer();

		enforcer.invalidate("hex");
		enforcer.invalidateAll();
		enforcer.markBudgeted("hex");
	}
}
