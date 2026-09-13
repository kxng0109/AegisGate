package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.redis.testcontainers.RedisContainer;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testcontainers.utility.DockerImageName.parse;

/**
 * Proves hold-then-settle against real Redis: admission hold charged into month counters, stream-end true-up by
 * delta, exactly-once replay, abort re-arm, missing-hold gap, rollover straddle, and absurd-actual fail-closed.
 * Zero-cost holds (H=0) settle cleanly without tripping the missing-hold path.
 */
@Testcontainers
@DisplayName("Settle Lua scripts against real Redis")
class SettleLuaIntegrationTest {

	@Container
	static final RedisContainer REDIS =
			new RedisContainer(parse("redis:8.8.2-alpine3.23"));

	private static StringRedisTemplate sharedTemplate;

	private static StringRedisTemplate template() {
		if (sharedTemplate == null) {
			org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
					new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
							REDIS.getHost(), REDIS.getMappedPort(6379));
			factory.afterPropertiesSet();
			sharedTemplate = new StringRedisTemplate(factory);
			sharedTemplate.afterPropertiesSet();
		}
		return sharedTemplate;
	}

	@BeforeEach
	void resetRedisState() {
		template().getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	private static DefaultRedisScript<List> script(String name) {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource(name));
		script.setResultType(List.class);
		return script;
	}

	private static BudgetEnforcer enforcer(StringRedisTemplate template) {
		CostCalculator calculator = mock(CostCalculator.class);
		when(calculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(0L);
		return new BudgetEnforcer(template, script("budget_limit.lua"), script("hold.lua"),
				script("settle.lua"), calculator, new SimpleMeterRegistry());
	}

	private static String currMonth() {
		return YearMonth.from(Instant.now().atZone(ZoneOffset.UTC)).toString();
	}

	private static void seedMonthCfg(StringRedisTemplate template, String level, String subject,
	                                 long monthMicros) {
		template.opsForHash().putAll(
				BudgetEnforcer.cfgKey(level, subject),
				Map.of("minute_micros", "0", "month_micros", Long.toString(monthMicros)));
	}

	private static long monthCount(StringRedisTemplate template, String level, String subject, String month) {
		String value = template.opsForValue().get(BudgetEnforcer.monthKey(level, subject, month));
		return value == null ? 0L : Long.parseLong(value);
	}

	private static String hex() {
		return "cd".repeat(32);
	}

	/** Simulates admission charging H, then records the hold (the controller's admit-then-hold order). */
	private static void admitAndHold(BudgetEnforcer enforcer, StringRedisTemplate template,
	                                 String holdId, String keyHex, long holdMicros, String month) {
		seedMonthCfg(template, "KEY", keyHex, 1_000_000_000L);
		template.opsForValue().increment(BudgetEnforcer.monthKey("KEY", keyHex, month), holdMicros);
		assertThat(enforcer.createHold(holdId, "KEY:" + keyHex, holdMicros, month,
				Instant.now().getEpochSecond(), 3600L)).isTrue();
	}

	@Test
	@DisplayName("first settle refunds the unused hold by delta")
	void firstSettleRefundsDelta() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();

		admitAndHold(enforcer, template, "hold-1", keyHex, 10_000L, month);

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-1", "KEY", keyHex, null, keyHex, month, month, 4_000L, 0L);

		assertThat(outcome.settled()).isTrue();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_OK);
		assertThat(outcome.amountApplied()).isEqualTo(4_000L);
		assertThat(outcome.gapSet()).isFalse();
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(4_000L);
	}

	@Test
	@DisplayName("settle replay is an idempotent no-op")
	void settleReplayIsNoop() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();

		admitAndHold(enforcer, template, "hold-2", keyHex, 10_000L, month);
		enforcer.settle("hold-2", "KEY", keyHex, null, keyHex, month, month, 4_000L, 0L);

		BudgetEnforcer.SettleOutcome replay = enforcer.settle(
				"hold-2", "KEY", keyHex, null, keyHex, month, month, 4_000L, 0L);

		assertThat(replay.settled()).isFalse();
		assertThat(replay.outcome()).isEqualTo(BudgetEnforcer.SETTLE_REPLAY);
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(4_000L);
	}

	@Test
	@DisplayName("abort settles input-known and re-arms the output hold")
	void abortSettlesAndRearms() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();

		admitAndHold(enforcer, template, "hold-3", keyHex, 10_000L, month);
		long abortDue = Instant.now().getEpochSecond() + 30L;

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-3", "KEY", keyHex, null, keyHex, month, month, 2_000L, abortDue);

		assertThat(outcome.settled()).isTrue();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_ABORTED);
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(2_000L);
		assertThat(template.opsForHash().get(BudgetEnforcer.holdKey("hold-3"), "state")).isEqualTo("ABORTED");
		Double score = template.opsForZSet().score(BudgetEnforcer.holdExpiryKey(),
				BudgetEnforcer.holdKey("hold-3"));
		assertThat(score).isNotNull();
		assertThat(score.longValue()).isEqualTo(abortDue);
	}

	@Test
	@DisplayName("expire-only path marks the hold and sets the gap marker")
	void expireOnlyMarksAndGaps() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();

		admitAndHold(enforcer, template, "hold-4", keyHex, 10_000L, month);

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-4", "KEY", keyHex, null, keyHex, month, month, -1L, 0L);

		assertThat(outcome.settled()).isFalse();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_EXPIRED);
		assertThat(outcome.gapSet()).isTrue();
		assertThat(template.opsForHash().get(BudgetEnforcer.holdKey("hold-4"), "state")).isEqualTo("EXPIRED");
		assertThat(template.hasKey(BudgetEnforcer.gapKey("KEY", keyHex, month))).isTrue();
		// H stays counted: safe over-count direction, never silently dropped.
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(10_000L);
	}

	@Test
	@DisplayName("settle with a missing hold record reports expired with a gap")
	void missingHoldReportsExpiredGap() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();
		seedMonthCfg(template, "KEY", keyHex, 1_000_000_000L);

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-gone", "KEY", keyHex, null, keyHex, month, month, 4_000L, 0L);

		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_EXPIRED);
		assertThat(outcome.gapSet()).isTrue();
		assertThat(monthCount(template, "KEY", keyHex, month)).isZero();
	}

	@Test
	@DisplayName("rollover straddle refunds the original month and charges the current month")
	void rolloverStraddleSplitsMonths() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String origMonth = "2026-08";
		String month = currMonth();
		Assumptions.assumeTrue(!origMonth.equals(month), "needs a distinct orig month");

		seedMonthCfg(template, "KEY", keyHex, 1_000_000_000L);
		template.opsForValue().increment(BudgetEnforcer.monthKey("KEY", keyHex, origMonth), 10_000L);
		assertThat(enforcer.createHold("hold-5", "KEY:" + keyHex, 10_000L, origMonth,
				Instant.now().getEpochSecond(), 3600L)).isTrue();

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-5", "KEY", keyHex, null, keyHex, origMonth, month, 4_000L, 0L);

		assertThat(outcome.settled()).isTrue();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_OK);
		assertThat(outcome.gapSet()).isTrue();
		assertThat(monthCount(template, "KEY", keyHex, origMonth)).isZero();
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(4_000L);
	}

	@Test
	@DisplayName("absurd actual fails closed with a gap and moves no money")
	void absurdActualFailsClosed() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();

		admitAndHold(enforcer, template, "hold-6", keyHex, 10_000L, month);

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-6", "KEY", keyHex, null, keyHex, month, month,
				BudgetEnforcer.MAX_EXACT_LUA_INTEGER + 1L, 0L);

		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_EXPIRED);
		assertThat(outcome.gapSet()).isTrue();
		assertThat(monthCount(template, "KEY", keyHex, month)).isEqualTo(10_000L);
	}

	@Test
	@DisplayName("zero hold settles cleanly without tripping the missing-hold path")
	void zeroHoldSettlesCleanly() {
		StringRedisTemplate template = template();
		BudgetEnforcer enforcer = enforcer(template);
		String keyHex = hex();
		String month = currMonth();
		seedMonthCfg(template, "KEY", keyHex, 1_000_000_000L);

		assertThat(enforcer.createHold("hold-7", "KEY:" + keyHex, 0L, month,
				Instant.now().getEpochSecond(), 3600L)).isTrue();

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-7", "KEY", keyHex, null, keyHex, month, month, 0L, 0L);

		assertThat(outcome.settled()).isTrue();
		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_OK);
		assertThat(outcome.gapSet()).isFalse();
		assertThat(monthCount(template, "KEY", keyHex, month)).isZero();
	}
}
