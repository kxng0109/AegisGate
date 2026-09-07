package io.github.kxng0109.aegisgate.proxy.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProviderCircuitBreaker reset() race-free force-close under contention")
class ProviderCircuitBreakerResetConcurrencyTest {

	private static ProviderCircuitBreaker openBreaker() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
				"p", Clock.fixed(Instant.now(), ZoneId.of("UTC")), 3, Duration.ofSeconds(30));
		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		return breaker;
	}

	@Test
	@DisplayName("reset is immediate close under contention")
	void resetIsImmediateCloseUnderContention() throws Exception {
		ProviderCircuitBreaker breaker = openBreaker();
		int tasks = 1_000;
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(tasks);
			for (int i = 0; i < tasks; i++) {
				pool.submit(() -> {
					try {
						start.await();
						if (Thread.currentThread().hashCode() % 2 == 0) {
							breaker.reset();
						} else {
							breaker.getState();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		}
		breaker.reset();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.getFailureCount()).isZero();
		assertThat(breaker.tryAcquire()).isTrue();
	}

	@Test
	@DisplayName("failures during reset never leave stale count after quiesce")
	void failuresDuringResetNeverLeaveStaleCount() throws Exception {
		ProviderCircuitBreaker breaker = openBreaker();
		int tasks = 10_000;
		AtomicInteger resets = new AtomicInteger();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(tasks);
			for (int i = 0; i < tasks; i++) {
				final int taskIndex = i;
				pool.submit(() -> {
					try {
						start.await();
						if (taskIndex % 10 == 0) {
							breaker.reset();
							resets.incrementAndGet();
						} else {
							breaker.recordFailure();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(resets.get()).isPositive();
		breaker.reset();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.getFailureCount()).isZero();
		assertThat(breaker.tryAcquire()).isTrue();
	}
}
