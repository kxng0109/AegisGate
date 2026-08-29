package io.github.kxng0109.aegisgate.proxy.failover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A per provider trip switch that stops the gateway from wasting time and
 * connection slots on a provider that is clearly failing.
 *
 * <p>The state machine follows the canonical circuit breaker pattern:</p>
 * <ul>
 *   <li><b>CLOSED</b> is normal operation. Failures are counted; a
 *       consecutive failure count reaching the threshold trips the circuit to
 *       OPEN. Any success resets the count.</li>
 *   <li><b>OPEN</b> rejects every attempt immediately without contacting the
 *       provider. After the cooldown has elapsed, the next attempt moves the
 *       circuit to HALF_OPEN and becomes a probe.</li>
 *   <li><b>HALF_OPEN</b> allows exactly one probe at a time. A successful
 *       probe closes the circuit; a failed probe reopens it and restarts the
 *       cooldown.</li>
 * </ul>
 *
 * <p>Design notes, grounded in the resilience4j and Fowler references:</p>
 * <ul>
 *   <li>State lives in an {@link AtomicReference} and transitions use
 *       compare and set, so the hot path is lock free and costs nanoseconds
 *       even under high concurrency.</li>
 *   <li>The protected network call is never inside the critical section.
 *       {@link #tryAcquire()} only grants permission; the call itself happens
 *       outside, which is what keeps throughput high.</li>
 *   <li>Transitions out of OPEN happen lazily on the next attempt, so no
 *       background thread or timer ever exists.</li>
 *   <li>Time is read from an injected {@link Clock} so tests can advance the
 *       cooldown deterministically instead of sleeping.</li>
 * </ul>
 */
public final class ProviderCircuitBreaker {

	/**
	 * How many consecutive failures trip the circuit, when not configured otherwise.
	 */
	public static final int DEFAULT_FAILURE_THRESHOLD = 3;

	/**
	 * How long the circuit stays open before a probe is allowed, when not configured otherwise.
	 */
	public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

	/**
	 * The three states of the state machine.
	 */
	public enum State {
		/**
		 * Normal operation; failures are being counted.
		 */
		CLOSED,
		/**
		 * Rejecting all attempts until the cooldown elapses.
		 */
		OPEN,
		/**
		 * Allowing exactly one probe to test the provider.
		 */
		HALF_OPEN
	}

	private final String providerName;
	private final int failureThreshold;
	private final Duration cooldown;
	private final Clock clock;

	private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final AtomicInteger halfOpenProbes = new AtomicInteger();

	private volatile Instant openedAt = Instant.EPOCH;

	/**
	 * Creates a breaker with the default threshold and cooldown and the system clock.
	 *
	 * @param providerName label used for logging, typically the provider config name
	 */
	public ProviderCircuitBreaker(String providerName) {
		this(providerName, Clock.systemUTC(), DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN);
	}

	/**
	 * Creates a breaker with the default threshold and cooldown and an explicit clock.
	 *
	 * @param providerName label used for logging
	 * @param clock        time source used for the cooldown
	 */
	public ProviderCircuitBreaker(String providerName, Clock clock) {
		this(providerName, clock, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN);
	}

	/**
	 * Creates a breaker with explicit tuning and clock. Intended for tests and
	 * operator tuning; the defaults are documented on
	 * {@link #DEFAULT_FAILURE_THRESHOLD} and {@link #DEFAULT_COOLDOWN}.
	 *
	 * @param providerName     label used for logging
	 * @param clock            time source used for the cooldown
	 * @param failureThreshold consecutive failures before the circuit opens,
	 *                         must be positive
	 * @param cooldown         how long the circuit stays open, must not be negative
	 * @throws IllegalArgumentException when the tuning values are invalid
	 */
	public ProviderCircuitBreaker(
			String providerName,
			Clock clock,
			int failureThreshold,
			Duration cooldown
	) {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock must not be null");
		}
		if (failureThreshold <= 0) {
			throw new IllegalArgumentException("failureThreshold must be positive, was " + failureThreshold);
		}
		if (cooldown == null || cooldown.isNegative()) {
			throw new IllegalArgumentException("cooldown must not be negative");
		}
		this.providerName = providerName;
		this.clock = clock;
		this.failureThreshold = failureThreshold;
		this.cooldown = cooldown;
	}

	/**
	 * Asks whether a call may proceed right now.
	 *
	 * <p>Returns {@code true} when the circuit is CLOSED, or when this caller
	 * wins the single probe slot in HALF_OPEN. Returns {@code false} when the
	 * circuit is OPEN and the cooldown has not elapsed, when the cooldown just
	 * elapsed but another caller already took the probe, or when a probe is
	 * already in flight.</p>
	 *
	 * @return {@code true} when the caller is permitted to attempt the call
	 */
	public boolean tryAcquire() {
		while (true) {
			State current = state.get();
			switch (current) {
				case CLOSED -> {
					return true;
				}
				case OPEN -> {
					if (cooldownElapsed()) {
						if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
							return halfOpenProbes.compareAndSet(0, 1);
						}
					} else {
						return false;
					}
				}
				case HALF_OPEN -> {
					return halfOpenProbes.compareAndSet(0, 1);
				}
			}
		}
	}

	/**
	 * Records a successful call.
	 *
	 * <p>In CLOSED the failure count is reset. In HALF_OPEN the circuit closes
	 * and normal operation resumes. No effect in OPEN.</p>
	 */
	public void recordSuccess() {
		while (true) {
			State current = state.get();
			if (current == State.HALF_OPEN) {
				if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
					halfOpenProbes.set(0);
					consecutiveFailures.set(0);
					return;
				}
			} else {
				consecutiveFailures.set(0);
				return;
			}
		}
	}

	/**
	 * Records a transient failure.
	 *
	 * <p>In CLOSED the failure count is incremented and the circuit opens when
	 * it reaches the threshold. In HALF_OPEN the circuit reopens and the
	 * cooldown restarts. No effect in OPEN.</p>
	 */
	public void recordFailure() {
		while (true) {
			State current = state.get();
			switch (current) {
				case CLOSED -> {
					if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
						if (state.compareAndSet(State.CLOSED, State.OPEN)) {
							openedAt = clock.instant();
							return;
						}
					} else {
						return;
					}
				}
				case HALF_OPEN -> {
					if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
						openedAt = clock.instant();
						halfOpenProbes.set(0);
						return;
					}
				}
				case OPEN -> {
					return;
				}
			}
		}
	}

	/**
	 * @return the current state, useful for logging and tests
	 */
	public State getState() {
		return state.get();
	}

	/**
	 * @return how many consecutive failures are currently recorded in CLOSED
	 */
	public int getFailureCount() {
		return consecutiveFailures.get();
	}

	/**
	 * @return the provider this breaker protects
	 */
	public String getProviderName() {
		return providerName;
	}

	private boolean cooldownElapsed() {
		return clock.instant().isAfter(openedAt.plus(cooldown));
	}
}