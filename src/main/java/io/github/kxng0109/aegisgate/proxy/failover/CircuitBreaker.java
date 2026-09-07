package io.github.kxng0109.aegisgate.proxy.failover;

/**
 * Contract for a per-provider circuit breaker.
 *
 * <p>The production implementation ({@link RedisCircuitBreaker}) stores breaker state in Redis so that every gateway
 * instance coordinates on a shared view of which providers are healthy. An in-memory implementation
 * ({@link ProviderCircuitBreaker}) is retained as a fast local mirror (used as a read-through-with-timeout fallback
 * when Redis is slow or unavailable) and for unit testing the state-machine logic without Redis.</p>
 */
public interface CircuitBreaker {

	/**
	 * The three states of the canonical circuit-breaker state machine.
	 */
	enum State {
		/**
		 * Normal operation; failures are being counted.
		 */
		CLOSED,
		/**
		 * Rejecting all attempts until the cooldown elapses.
		 */
		OPEN,
		/**
		 * Allowing exactly one probe (the owner) to test the provider.
		 */
		HALF_OPEN
	}

	/**
	 * Asks whether a call to the provider may proceed right now.
	 *
	 * @return {@code true} when the caller is permitted to attempt the call
	 */
	boolean tryAcquire();

	/**
	 * Records a successful call (closes the circuit / resets the failure count).
	 */
	void recordSuccess();

	/**
	 * Force-resets this circuit breaker to the {@link State#CLOSED} state, clearing the failure count and any in-flight
	 * probe. Intended for operator/admin use; unlike {@link #recordSuccess()}, this transitions from {@link State#OPEN}
	 * (and {@link State#HALF_OPEN}) to CLOSED unconditionally.
	 *
	 * <p>The transition is atomic and lock-free on the hot path. Implementations must clear derived
	 * counters before-or-with the state flip so a concurrent {@link #recordFailure()} can never observe CLOSED with a
	 * stale failure count.</p>
	 */
	void reset();

	/**
	 * Records a transient failure (may open the circuit when the threshold is reached).
	 */
	void recordFailure();

	/**
	 * @return the current state, useful for logging and metrics
	 */
	State getState();

	/**
	 * @return how many consecutive failures are currently recorded in CLOSED
	 */
	int getFailureCount();

	/**
	 * @return the provider this breaker protects
	 */
	String getProviderName();
}