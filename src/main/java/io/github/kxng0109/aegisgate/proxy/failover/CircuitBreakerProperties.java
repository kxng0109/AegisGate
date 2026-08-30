package io.github.kxng0109.aegisgate.proxy.failover;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tunables for the distributed (Redis-backed) circuit breaker.
 *
 * <p>Bound from {@code gateway.circuit-breaker.*}. A {@code Duration} binds directly from a millisecond string such as
 * {@code 250ms}; the default unit is milliseconds, so a bare {@code 250} is also 250ms.</p>
 */
@ConfigurationProperties(prefix = "gateway.circuit-breaker")
public record CircuitBreakerProperties(
		/**
		 * Bounded command timeout for breaker reads/writes against the dedicated breaker Redis template. Kept short so a
		 * slow Redis fails fast to the in-memory mirror instead of stalling virtual threads. Hot-path ceiling should stay
		 * well under 2000ms; 250ms is ample because a healthy LAN Redis answers in sub-millisecond.
		 */
		@DefaultValue("250ms") Duration redisTimeout,
		/**
		 * Consecutive failures that trip the circuit from CLOSED to OPEN.
		 */
		@DefaultValue("3") int failureThreshold,
		/**
		 * How long the circuit stays OPEN before a single probe is allowed.
		 */
		@DefaultValue("30s") Duration cooldown,
		/**
		 * Maximum age of a HALF_OPEN probe ownership before another instance may steal the probe (prevents a wedged
		 * circuit if the probe owner crashes mid-probe).
		 */
		@DefaultValue("60s") Duration probeLease,
		/**
		 * Maximum number of concurrent breaker Redis operations; a {@code Semaphore} of this size bounds how many virtual
		 * threads can be awaiting Redis at once, so a Redis stall cannot exhaust the thread pool.
		 */
		@DefaultValue("256") int bulkheadPermits
) {
}