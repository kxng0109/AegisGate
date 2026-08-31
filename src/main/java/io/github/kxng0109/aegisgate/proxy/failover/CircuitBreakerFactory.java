package io.github.kxng0109.aegisgate.proxy.failover;

import java.util.Map;
import java.util.Set;

/**
 * Creates and caches the circuit breaker for each provider.
 *
 * <p>The production implementation ({@link RedisCircuitBreakerFactory}) returns the distributed, Redis-backed breaker;
 * callers should depend on this interface so the breaker topology can be swapped for tests.</p>
 */
public interface CircuitBreakerFactory {

	/**
	 * @return the breaker for the named provider (never null)
	 */
	CircuitBreaker get(String providerName);

	/**
	 * @return the set of configured provider names (never null)
	 */
	Set<String> providerNames();

	/**
	 * Clears local caches and best-effort deletes the Redis breaker keys.
	 */
	void reset();

	/**
	 * Clears local cache and resets the Redis breaker state for a specific provider.
	 *
	 * @param providerName provider whose breaker is to be reset
	 * @return the new state of the provider
	 */
	default CircuitBreaker.State reset(String providerName) {
		reset();
		return CircuitBreaker.State.CLOSED;
	}

	/**
	 * @return a snapshot of each provider's state for metrics (never null)
	 */
	Map<String, CircuitBreaker.State> states();
}