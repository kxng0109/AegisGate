package io.github.kxng0109.aegisgate.budget;

import java.util.List;
import java.util.UUID;

import com.redis.testcontainers.RedisContainer;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
 * Tripwire for the admission-gate hot path: N full {@code budget_limit.lua} round trips against real Redis must
 * sustain a floor throughput with no single-call blowup. Bounds are deliberately generous (shared CI runners are
 * slow) — this guards against order-of-magnitude regressions, not for proving the 200K ceiling, which needs the
 * distributed k6 harness.
 */
@Testcontainers
@DisplayName("Gate throughput smoke against real Redis")
class GateThroughputSmokeTest {

	private static final int WARMUP_CALLS = 200;
	private static final int MEASURED_CALLS = 2000;
	private static final double MIN_DECISIONS_PER_SECOND = 200.0;
	private static final long MAX_SINGLE_CALL_NANOS = 5_000_000_000L;

	@Container
	static final RedisContainer REDIS =
			new RedisContainer(parse("redis:8.10.1-alpine3.23"));

	private static StringRedisTemplate sharedTemplate;

	private static StringRedisTemplate template() {
		if (sharedTemplate == null) {
			LettuceConnectionFactory factory =
					new LettuceConnectionFactory(
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
		script.setLocation(new ClassPathResource(name));
		script.setResultType(List.class);
		return script;
	}

	private static BudgetEnforcer enforcer(StringRedisTemplate template) {
		CostCalculator calculator = mock(CostCalculator.class);
		when(calculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(0L);
		return new BudgetEnforcer(template, script("budget_limit.lua"), script("hold.lua"),
				script("settle.lua"), calculator, new SimpleMeterRegistry());
	}

	@Test
	@DisplayName("2000 gate decisions sustain the floor with no single-call blowup")
	void gateDecisionsSustainFloorThroughput() {
		BudgetEnforcer enforcer = enforcer(template());
		for (int i = 0; i < WARMUP_CALLS; i++) {
			decide(enforcer, i);
		}
		long maxSingleNanos = 0L;
		long start = System.nanoTime();
		for (int i = WARMUP_CALLS; i < WARMUP_CALLS + MEASURED_CALLS; i++) {
			long callStart = System.nanoTime();
			decide(enforcer, i);
			long elapsed = System.nanoTime() - callStart;
			if (elapsed > maxSingleNanos) {
				maxSingleNanos = elapsed;
			}
		}
		double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
		double decisionsPerSecond = MEASURED_CALLS / seconds;
		assertThat(decisionsPerSecond)
				.as("gate decisions/s floor (warm, real Redis)")
				.isGreaterThanOrEqualTo(MIN_DECISIONS_PER_SECOND);
		assertThat(maxSingleNanos)
				.as("slowest single gate decision")
				.isLessThan(MAX_SINGLE_CALL_NANOS);
	}

	private static void decide(BudgetEnforcer enforcer, int salt) {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-smoke-" + salt + "-" + UUID.randomUUID());
		BudgetDecision decision = enforcer.checkBudget(hash, null, ProviderType.OPENAI, "fast", 10, null);
		assertThat(decision).isInstanceOf(BudgetDecision.Allowed.class);
	}
}
