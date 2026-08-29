package io.github.kxng0109.aegisgate.proxy.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProviderCircuitBreaker}: every state transition, the
 * cooldown behaviour, probe semantics, concurrency safety, and configuration
 * validation. Time is controlled through a mutable clock so no test ever
 * sleeps.
 */
@DisplayName("ProviderCircuitBreaker")
class ProviderCircuitBreakerTest {

	private static final Instant START = Instant.parse("2026-08-29T00:00:00Z");

	@Test
	@DisplayName("a fresh breaker is closed and admits calls")
	void initiallyClosedAndAdmitting() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", mutableClock());
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
		assertTrue(breaker.tryAcquire());
	}

	@Test
	@DisplayName("a success resets the failure count")
	void successResetsFailureCount() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", mutableClock(), 3, Duration.ofSeconds(30));
		breaker.recordFailure();
		breaker.recordFailure();
		assertEquals(2, breaker.getFailureCount());
		breaker.recordSuccess();
		assertEquals(0, breaker.getFailureCount());
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
	}

	@Test
	@DisplayName("the circuit opens once the failure threshold is reached")
	void opensAfterThreshold() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", mutableClock(), 2, Duration.ofSeconds(30));
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());
		assertFalse(breaker.tryAcquire());
	}

	@Test
	@DisplayName("an open circuit rejects calls until the cooldown elapses")
	void rejectsWhileOpenBeforeCooldown() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());

		clock.advance(Duration.ofSeconds(10));
		assertFalse(breaker.tryAcquire(), "still inside the cooldown window");

		clock.advance(Duration.ofSeconds(21));
		assertTrue(breaker.tryAcquire(), "cooldown elapsed, a probe is allowed");
	}

	@Test
	@DisplayName("exactly one probe is allowed at a time")
	void onlyOneProbeAtATime() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));

		assertTrue(breaker.tryAcquire(), "first caller takes the probe");
		assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState());
		assertFalse(breaker.tryAcquire(), "second caller must wait for the probe to finish");
	}

	@Test
	@DisplayName("a successful probe closes the circuit")
	void successfulProbeCloses() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire());

		breaker.recordSuccess();
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
		assertTrue(breaker.tryAcquire(), "closed circuit admits calls again");
	}

	@Test
	@DisplayName("a failed probe reopens the circuit and restarts the cooldown")
	void failedProbeReopens() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire());

		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());
		assertFalse(breaker.tryAcquire(), "cooldown restarted, still open");
		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire(), "next probe allowed after the restarted cooldown");
	}

	@Test
	@DisplayName("recording results while open leaves the circuit untouched")
	void recordingWhileOpenIsIgnored() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		breaker.recordSuccess();
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());
	}

	@Test
	@DisplayName("under concurrency exactly one caller wins the probe")
	void exactlyOneProbeUnderConcurrency() throws Exception {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));

		int threads = 32;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger admitted = new AtomicInteger();
		try {
			for (int i = 0; i < threads; i++) {
				executor.submit(() -> {
					ready.countDown();
					try {
						go.await(5, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					if (breaker.tryAcquire()) {
						admitted.incrementAndGet();
					}
				});
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS), "all threads must become ready");
			go.countDown();
			executor.shutdown();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "threads must finish");
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, admitted.get(), "exactly one probe must be granted");
		assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState());
	}

	@Test
	@DisplayName("accessors expose the provider name, state and failure count")
	void accessorsExposeState() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 2, Duration.ofSeconds(30));
		assertEquals("p1", breaker.getProviderName());
		assertEquals(0, breaker.getFailureCount());
		breaker.recordFailure();
		assertEquals(1, breaker.getFailureCount());
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
	}

	@Test
	@DisplayName("the cooldown only expires strictly after the wait duration")
	void cooldownBoundaryIsStrict() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 1, Duration.ofSeconds(30));
		breaker.recordFailure();

		clock.advance(Duration.ofSeconds(30));
		assertFalse(breaker.tryAcquire(), "exactly at the boundary the circuit is still open");

		clock.advance(Duration.ofSeconds(1));
		assertTrue(breaker.tryAcquire());
	}

	@Test
	@DisplayName("the single argument constructor uses defaults and the system clock")
	void singleArgumentConstructorDefaults() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1");
		assertEquals("p1", breaker.getProviderName());
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
		assertTrue(breaker.tryAcquire());
	}

	@Test
	@DisplayName("a null clock is rejected")
	void nullClockRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new ProviderCircuitBreaker("p1", null, 3, Duration.ofSeconds(30)));
	}

	@Test
	@DisplayName("a null provider name is rejected")
	void nullProviderRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new ProviderCircuitBreaker(null, mutableClock(), 3, Duration.ofSeconds(30)));
	}

	@Test
	@DisplayName("two consecutive failures exactly at the threshold open the circuit")
	void duplicateConsecutiveFailuresOpen() {
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", mutableClock(), 3, Duration.ofSeconds(30));
		assertEquals(0, breaker.getFailureCount());
		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());
	}

	@Test
	@DisplayName("a failed probe in HALF_OPEN reopens and restarts the cooldown")
	void failedProbeReopensAfterHalfOpen() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 2, Duration.ofSeconds(30));
		breaker.recordFailure();
		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState());

		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire());
		assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState());

		breaker.recordFailure();
		assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState(),
				"a failed probe must reopen the circuit");
		assertFalse(breaker.tryAcquire(), "the reopened circuit must reject until the cooldown restarts");
	}

	@Test
	@DisplayName("a success after a reopened circuit closes it again")
	void successAfterReopenCloses() {
		MutableClock clock = mutableClock();
		ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p1", clock, 2, Duration.ofSeconds(30));
		breaker.recordFailure();
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire());
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));
		assertTrue(breaker.tryAcquire());
		breaker.recordSuccess();
		assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState());
	}

	@Test
	@DisplayName("invalid tuning values are rejected")
	void invalidConfigurationRejected() {
		MutableClock clock = mutableClock();
		assertThrows(IllegalArgumentException.class,
		             () -> new ProviderCircuitBreaker("p1", clock, 0, Duration.ofSeconds(30))
		);
		assertThrows(IllegalArgumentException.class,
		             () -> new ProviderCircuitBreaker("p1", clock, 3, Duration.ofSeconds(-1))
		);
		assertThrows(IllegalArgumentException.class,
		             () -> new ProviderCircuitBreaker("  ", clock, 3, Duration.ofSeconds(30))
		);
	}

	private static MutableClock mutableClock() {
		return new MutableClock(START);
	}

	/**
	 * A clock whose current instant tests can move forward deterministically.
	 */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant start) {
			this.now = start;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}