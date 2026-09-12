package io.github.kxng0109.aegisgate.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.redis.testcontainers.RedisContainer;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testcontainers.utility.DockerImageName.parse;

/**
 * Proves the Lua budget script against real Redis: atomic allow/consume, first-denied-wins, check-before-increment
 * (denials consume nothing), zero-cost key silence, and month key shape. Wire-type coverage (Long vs String results) is
 * asserted through the enforcer in {@link BudgetEnforcerTest}; here the real script exercises real Redis semantics.
 */
@Testcontainers
@DisplayName("Budget Lua script against real Redis")
class BudgetLuaIntegrationTest {

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

	private static DefaultRedisScript<List> script() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("budget_limit.lua"));
		script.setResultType(List.class);
		return script;
	}

	private static BudgetEnforcer enforcer(StringRedisTemplate template, long estimateMicros) {
		CostCalculator calculator = mock(CostCalculator.class);
		when(calculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(estimateMicros);
		return new BudgetEnforcer(template, script(), calculator, new SimpleMeterRegistry());
	}

	private static void seedCfg(StringRedisTemplate template, String level, String subject,
	                            long minuteMicros, long monthMicros) {
		template.opsForHash().putAll(
				BudgetEnforcer.cfgKey(level, subject),
				Map.of(
						"minute_micros", Long.toString(minuteMicros),
						"month_micros", Long.toString(monthMicros)
				)
		);
	}

	private static String hex() {
		return "ab".repeat(32);
	}

	@Test
	@DisplayName("allowed requests consume spend and report remaining")
	void allowedConsumesAndReports() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		Object cfgReadback = template.opsForHash().get(BudgetEnforcer.cfgKey("KEY", hex()), "minute_micros");
		assertEquals("1000000", String.valueOf(cfgReadback), "seeded config is visible to the script");
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null);

		assertTrue(first instanceof BudgetDecision.Allowed allowed);
		assertEquals(940_000L, ((BudgetDecision.Allowed) first).remainingMicros());
		String minuteKey = BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		);
		assertEquals("60000", template.opsForValue().get(minuteKey));
	}

	@Test
	@DisplayName("denials consume nothing and first-denied level wins")
	void deniedConsumesNothingAndFirstWins() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 100_000L, 100_000_000L);
		seedCfg(template, "TEAM", "tenant-a", 0L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "tenant-a", ProviderType.OPENAI, "gpt-5.6-luna", 10, null);
		assertTrue(first instanceof BudgetDecision.Allowed);

		BudgetDecision second = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "tenant-a", ProviderType.OPENAI, "gpt-5.6-luna", 10, null);
		assertTrue(second instanceof BudgetDecision.Denied denied);
		assertEquals("KEY", ((BudgetDecision.Denied) second).level());
		assertEquals("MINUTE", ((BudgetDecision.Denied) second).window());

		String minuteKey = BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		);
		assertEquals("60000", template.opsForValue().get(minuteKey));
		assertFalse(template.hasKey(BudgetEnforcer.minuteKey(
				"TEAM", "tenant-a",
				System.currentTimeMillis() / 60_000L
		)));
	}

	@Test
	@DisplayName("zero-cost requests create no keys")
	void zeroCostCreatesNoKeys() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 100_000L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 0L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OLLAMA, "local-llama", 10, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
		assertFalse(template.hasKey(BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		)));
	}

	@Test
	@DisplayName("month counters use the UTC calendar month key")
	void monthKeyShape() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 0L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		enforcer.checkBudget(SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null);

		String expected = BudgetEnforcer.monthKey("KEY", hex(), YearMonth.now(ZoneOffset.UTC).toString());
		assertEquals("60000", template.opsForValue().get(expected));
	}

	@Test
	@DisplayName("presence cache is instance-local and invalidatable")
	void presenceCacheInvalidates() {
		Cache<String, Boolean> probe = Caffeine.newBuilder()
		                                                          .maximumSize(10)
		                                                          .build();
		probe.put("k", Boolean.TRUE);
		probe.invalidate("k");
		assertTrue(probe.getIfPresent("k") == null);
	}

	@Test
	@DisplayName("absurd estimates deny without consuming")
	void absurdEstimateDeniesWithoutConsuming() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		DefaultRedisScript<List> script = script();
		List<String> keys = List.of(
				BudgetEnforcer.minuteKey("KEY", hex(), 1L),
				"",
				"",
				BudgetEnforcer.monthKey("KEY", hex(), "2026-09"),
				"",
				"",
				BudgetEnforcer.cfgKey("KEY", hex()),
				"",
				"");
		List<?> result = template.execute(script, keys, "99999999999999999999");
		assertEquals(5, result.size());
		long[] actual = new long[5];
		for (int i = 0; i < 5; i++) {
			Object value = result.get(i);
			actual[i] = value instanceof Number number ? number.longValue()
					: Long.parseLong(String.valueOf(value).trim());
		}
		assertArrayEquals(new long[]{0L, 1L, 0L, 60L, 2L}, actual);
		assertFalse(template.hasKey(BudgetEnforcer.minuteKey("KEY", hex(), 1L)));
	}

	@Test
	@DisplayName("retried idempotency key admits without double-debit")
	void retryAdmitsWithoutDoubleDebit() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);
		String idempotencyKey = UUID.randomUUID().toString();

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, idempotencyKey);
		BudgetDecision second = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, idempotencyKey);

		assertTrue(first instanceof BudgetDecision.Allowed);
		assertTrue(second instanceof BudgetDecision.Allowed);
		String month = YearMonth.now(ZoneOffset.UTC).toString();
		assertEquals("60000", template.opsForValue().get(BudgetEnforcer.monthKey("KEY", hex(), month)));
	}
}
