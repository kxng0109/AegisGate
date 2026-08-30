package io.github.kxng0109.aegisgate.proxy.failover;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RedisCircuitBreaker} against a real Redis instance (Testcontainers): the Lua state
 * machine (CLOSED to OPEN to HALF_OPEN), single-flight probe ownership across instances, recovery through
 * {@code recordSuccess}/{@code recordFailure}, and the fail-closed mirror fallback when Redis throws a
 * {@link org.springframework.dao.DataAccessException}.
 *
 * <p>Tests use a short 250ms cooldown so the cooldown window can be waited out with a small sleep instead of a long
 * one. Each test uses its own provider name (own Redis key), so tests never share breaker state.</p>
 */
@Testcontainers
@DisplayName("RedisCircuitBreaker against real Redis")
class RedisCircuitBreakerTest {

	@Container
	static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

	static {
		// Start synchronously at class-load time so @BeforeAll can rely on the container being up.
		REDIS.start();
	}

	private static StringRedisTemplate breakerTemplate;
	private static DefaultRedisScript<Long> tryAcquireScript;
	private static DefaultRedisScript<Long> recordFailureScript;
	private static DefaultRedisScript<Long> recordSuccessScript;
	private static CircuitBreakerProperties props;
	private static Semaphore bulkhead;
	private static Clock clock;
	private static LettuceConnectionFactory connectionFactory;

	@BeforeAll
	static void connectToRedis() {
		connectionFactory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
		connectionFactory.afterPropertiesSet();
		breakerTemplate = new StringRedisTemplate(connectionFactory);
		breakerTemplate.afterPropertiesSet();
		tryAcquireScript = circuitScript("circuit_try_acquire.lua");
		recordFailureScript = circuitScript("circuit_record_failure.lua");
		recordSuccessScript = circuitScript("circuit_record_success.lua");
		props = new CircuitBreakerProperties(
				Duration.ofMillis(250), 3, Duration.ofMillis(250), Duration.ofSeconds(60), 256);
		bulkhead = new Semaphore(256);
		clock = Clock.systemUTC();
	}

	@AfterAll
	static void closeRedisConnection() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("a fresh breaker is CLOSED and admits calls")
	void freshBreakerIsClosedAndAdmits() {
		RedisCircuitBreaker breaker = breaker("fresh", InstanceId.generate().value());

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.tryAcquire()).isTrue();
	}

	@Test
	@DisplayName("three consecutive failures trip the circuit OPEN")
	void opensAfterFailureThreshold() {
		RedisCircuitBreaker breaker = breaker("threshold", InstanceId.generate().value());

		recordFailures(breaker, 3);

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(breaker.tryAcquire()).isFalse();
	}

	@Test
	@DisplayName("after the cooldown exactly one instance owns the HALF_OPEN probe and recovery transitions work")
	void probeOwnershipIsSingleFlightAndRecoveryTransitionsWork() {
		RedisCircuitBreaker owner = breaker("probe", InstanceId.generate().value());
		recordFailures(owner, 3);
		assertThat(owner.tryAcquire()).isFalse();

		waitForCooldown();
		assertThat(owner.tryAcquire()).isTrue();

		RedisCircuitBreaker other = breaker("probe", InstanceId.generate().value());
		assertThat(other.tryAcquire()).isFalse();
		assertThat(owner.tryAcquire()).isTrue();

		owner.recordSuccess();
		assertThat(owner.tryAcquire()).isTrue();

		recordFailures(owner, 3);
		assertThat(owner.tryAcquire()).isFalse();
		waitForCooldown();
		assertThat(owner.tryAcquire()).isTrue();

		owner.recordFailure();
		assertThat(owner.tryAcquire()).isFalse();
	}

	@Test
	@DisplayName("when Redis fails, the in-memory mirror verdict is enforced (fail-closed)")
	void fallsBackToMirrorWhenRedisIsUnavailable() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(
				"fallback",
				new ThrowingRedisTemplate(),
				tryAcquireScript,
				recordFailureScript,
				recordSuccessScript,
				props,
				InstanceId.generate().value(),
				clock,
				bulkhead
		);

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.tryAcquire()).isTrue();
	}

	private static RedisCircuitBreaker breaker(String providerName, String instanceId) {
		return new RedisCircuitBreaker(
				providerName,
				breakerTemplate,
				tryAcquireScript,
				recordFailureScript,
				recordSuccessScript,
				props,
				instanceId,
				clock,
				bulkhead
		);
	}

	private static DefaultRedisScript<Long> circuitScript(String resourceName) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(resourceName));
		script.setResultType(Long.class);
		return script;
	}

	private static void recordFailures(RedisCircuitBreaker breaker, int count) {
		for (int i = 0; i < count; i++) {
			breaker.recordFailure();
		}
	}

	private static void waitForCooldown() {
		try {
			Thread.sleep(500);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the breaker cooldown", ex);
		}
	}

	/**
	 * A {@link StringRedisTemplate} whose script executions always fail, simulating a Redis outage so the breaker's
	 * mirror fallback can be exercised without touching Redis.
	 */
	private static final class ThrowingRedisTemplate extends StringRedisTemplate {

		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			throw new RedisConnectionFailureException("simulated Redis outage");
		}
	}
}