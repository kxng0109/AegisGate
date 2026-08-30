package io.github.kxng0109.aegisgate.proxy.failover;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the cross-instance coordination of {@link RedisCircuitBreaker} against a real Redis instance
 * (Testcontainers): two breaker instances with different instance ids sharing one Redis template must agree on the
 * shared state — one instance tripping the circuit blocks the other, and after the cooldown exactly one of the two
 * racing instances wins the single HALF_OPEN probe; the winner's success then closes the circuit for both.
 */
@Testcontainers
@DisplayName("Cross-instance circuit breaker coordination through shared Redis")
class CircuitBreakerCrossInstanceIntegrationTest {

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
	@DisplayName("instances share the OPEN state, race for a single probe, and recover together")
	void crossInstanceCoordination() throws Exception {
		RedisCircuitBreaker instA = breaker("shared", "inst-A");
		RedisCircuitBreaker instB = breaker("shared", "inst-B");

		instA.recordFailure();
		instA.recordFailure();
		instA.recordFailure();
		assertThat(instA.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		assertThat(instB.tryAcquire()).isFalse();

		waitForCooldown();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		AtomicBoolean winnerA = new AtomicBoolean();
		AtomicBoolean winnerB = new AtomicBoolean();
		try {
			executor.submit(() -> attemptProbe(ready, go, winnerA, instA));
			executor.submit(() -> attemptProbe(ready, go, winnerB, instB));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
		}

		assertThat((winnerA.get() ? 1 : 0) + (winnerB.get() ? 1 : 0)).isEqualTo(1);

		if (winnerA.get()) {
			instA.recordSuccess();
		} else {
			instB.recordSuccess();
		}
		assertThat(instA.tryAcquire()).isTrue();
		assertThat(instB.tryAcquire()).isTrue();
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

	private static void waitForCooldown() {
		try {
			Thread.sleep(500);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the breaker cooldown", ex);
		}
	}

	private static void attemptProbe(
			CountDownLatch ready,
			CountDownLatch go,
			AtomicBoolean winner,
			RedisCircuitBreaker breaker
	) {
		ready.countDown();
		try {
			go.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		}
		winner.set(breaker.tryAcquire());
	}
}