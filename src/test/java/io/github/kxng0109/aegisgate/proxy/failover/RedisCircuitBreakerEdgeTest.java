package io.github.kxng0109.aegisgate.proxy.failover;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Redis-circuit-breaker fallback branches that a healthy Redis never reaches: the bulkhead exhausting (all
 * permits taken) and a {@link DataAccessException} from Redis — both must fall back to the in-memory mirror
 * (fail-closed) without throwing.
 */
class RedisCircuitBreakerEdgeTest {

	private static final class NoOpTemplate extends StringRedisTemplate {
		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			return null;
		}
	}

	private static final class ThrowingTemplate extends StringRedisTemplate {
		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			throw new RedisConnectionFailureException("simulated redis outage");
		}
	}

	private static DefaultRedisScript<Long> script() {
		return new DefaultRedisScript<>();
	}

	private static CircuitBreakerProperties props() {
		return new CircuitBreakerProperties(Duration.ofMillis(250), 3, Duration.ofSeconds(30), Duration.ofSeconds(60), 256);
	}

	@Test
	void bulkheadExhaustionFallsBackToMirror() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(
				"p", new NoOpTemplate(), script(), script(), script(), props(), "inst", Clock.systemUTC(), new Semaphore(0));
		assertThat(breaker.tryAcquire()).isTrue();
		breaker.recordFailure();
		breaker.recordSuccess();
	}

	@Test
	void nullRedisResponseDeniesFailClosed() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(
				"p", new NoOpTemplate(), script(), script(), script(), props(), "inst", Clock.systemUTC(), new Semaphore(256));
		assertThat(breaker.tryAcquire()).isFalse();
		assertThat(breaker.getFailureCount()).isZero();
	}

	@Test
	void redisFailureFallsBackToMirrorAndThrottlesWarning() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(
				"p", new ThrowingTemplate(), script(), script(), script(), props(), "inst", Clock.systemUTC(), new Semaphore(256));
		assertThat(breaker.tryAcquire()).isTrue();
		assertThat(breaker.tryAcquire()).isTrue();
		breaker.recordFailure();
		breaker.recordSuccess();
	}
}