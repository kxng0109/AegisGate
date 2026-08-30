package io.github.kxng0109.aegisgate.proxy.failover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Redis-backed distributed circuit breaker for one provider.
 *
 * <p>Breaker state lives in a single Redis hash per provider ({@code circuit:{provider}}) and every decision is made by
 * an atomic Lua script, so all gateway instances agree on the same state machine. Exactly one instance wins the
 * HALF_OPEN probe slot at a time: the ownership compare-and-set happens inside the Lua script, not via a lock with a
 * short TTL (a lock whose TTL is shorter than the probe duration would permit duplicate probes, and Redlock is unsafe
 * on non-fenced systems). Cooldown math inside the scripts uses the Redis server clock via {@code TIME}, which avoids
 * cross-instance clock skew on the shared state.</p>
 *
 * <p>Redis is always in the path and always authoritative; there is no Redis-free mode. The local {@link
 * ProviderCircuitBreaker} mirror is a purely in-memory latency fallback: when the bulkhead is saturated (Redis slow) or
 * the command fails ({@link DataAccessException}, e.g. {@code RedisCommandTimeoutException} wrapped as
 * {@code QueryTimeoutException}), the mirror's verdict is enforced — fail-closed, never fail-open. A {@link Semaphore}
 * bulkhead bounds how many virtual threads can be blocked in breaker Redis I/O at once, so a Redis stall cannot exhaust
 * the thread pool.</p>
 */
@Slf4j
public final class RedisCircuitBreaker implements CircuitBreaker {

	/**
	 * Redis key prefix for the per-provider breaker hash.
	 */
	static final String KEY_PREFIX = "circuit:";

	/**
	 * Minimum spacing between WARN log lines for the same provider, so a Redis outage at ~1000 RPS cannot produce a log
	 * storm.
	 */
	private static final long WARN_THROTTLE_MILLIS = 1_000L;

	/**
	 * The Lua scripts return exactly this value when the attempt is allowed.
	 */
	private static final long ALLOWED_RESULT = 1L;

	private final String providerName;
	private final StringRedisTemplate breakerTemplate;
	private final DefaultRedisScript<Long> tryAcquireScript;
	private final DefaultRedisScript<Long> recordFailureScript;
	private final DefaultRedisScript<Long> recordSuccessScript;
	private final CircuitBreakerProperties props;
	private final String instanceId;
	private final Clock clock;
	private final Semaphore bulkhead;
	private final String key;
	private final ProviderCircuitBreaker mirror;
	private final ConcurrentHashMap<String, Instant> lastWarn = new ConcurrentHashMap<>();

	/**
	 * Creates the distributed breaker for one provider.
	 *
	 * @param providerName        provider identifier, also embedded in the Redis key
	 * @param breakerTemplate     dedicated fast Redis template with a short command timeout (see
	 *                            {@link CircuitBreakerProperties#redisTimeout()})
	 * @param tryAcquireScript    atomic CAS script consulted on every attempt
	 * @param recordFailureScript script recording a transient failure
	 * @param recordSuccessScript script recording a success
	 * @param props               breaker tunables (threshold, cooldown, probe lease, bulkhead)
	 * @param instanceId          identity of this gateway instance, used to claim HALF_OPEN probe ownership
	 * @param clock               time source for the local mirror and log throttling
	 * @param bulkhead            shared semaphore bounding concurrent Redis operations across all providers
	 */
	public RedisCircuitBreaker(
			String providerName,
			StringRedisTemplate breakerTemplate,
			DefaultRedisScript<Long> tryAcquireScript,
			DefaultRedisScript<Long> recordFailureScript,
			DefaultRedisScript<Long> recordSuccessScript,
			CircuitBreakerProperties props,
			String instanceId,
			Clock clock,
			Semaphore bulkhead
	) {
		this.providerName = providerName;
		this.breakerTemplate = breakerTemplate;
		this.tryAcquireScript = tryAcquireScript;
		this.recordFailureScript = recordFailureScript;
		this.recordSuccessScript = recordSuccessScript;
		this.props = props;
		this.instanceId = instanceId;
		this.clock = clock;
		this.bulkhead = bulkhead;
		this.key = KEY_PREFIX + providerName;
		this.mirror = new ProviderCircuitBreaker(providerName, clock);
	}

	/**
	 * Asks whether a call to this provider may proceed right now.
	 *
	 * <p>The verdict is computed atomically in Redis so all instances coordinate; when Redis is slow (bulkhead
	 * saturated) or unavailable, the in-memory mirror decides and its verdict is enforced (fail-closed, never
	 * fail-open).</p>
	 *
	 * @return {@code true} when the caller is permitted to attempt the call
	 */
	@Override
	public boolean tryAcquire() {
		if (!bulkhead.tryAcquire()) {
			return mirror.tryAcquire();
		}
		try {
			Long allowed = breakerTemplate.execute(
					tryAcquireScript,
					List.of(key),
					instanceId,
					String.valueOf(props.cooldown().toMillis()),
					String.valueOf(props.failureThreshold()),
					String.valueOf(props.probeLease().toMillis())
			);
			return allowed != null && allowed == ALLOWED_RESULT;
		} catch (DataAccessException ex) {
			warnThrottled("Redis read failed; the in-memory mirror verdict is enforced (fail-closed)", ex);
			return mirror.tryAcquire();
		} finally {
			bulkhead.release();
		}
	}

	/**
	 * Records a transient failure of this provider call.
	 *
	 * <p>The mirror always reflects this instance's own outcome first (so the fallback verdict is never stale); the
	 * shared Redis state is then updated best-effort so other instances see the failure too.</p>
	 */
	@Override
	public void recordFailure() {
		// Do NOT re-mirror from the Redis result: the mirror already reflects this instance's outcome.
		mirror.recordFailure();
		if (!bulkhead.tryAcquire()) {
			return;
		}
		try {
			breakerTemplate.execute(
					recordFailureScript,
					List.of(key),
					String.valueOf(clock.instant().toEpochMilli()),
					String.valueOf(props.failureThreshold())
			);
		} catch (DataAccessException ex) {
			warnThrottled("Redis write failed; the failure stays local to this instance", ex);
		} finally {
			bulkhead.release();
		}
	}

	/**
	 * Records a successful call to this provider.
	 *
	 * <p>The mirror always reflects this instance's own outcome first (so the fallback verdict is never stale); the
	 * shared Redis state is then updated best-effort so other instances see the success too.</p>
	 */
	@Override
	public void recordSuccess() {
		// Do NOT re-mirror from the Redis result: the mirror already reflects this instance's outcome.
		mirror.recordSuccess();
		if (!bulkhead.tryAcquire()) {
			return;
		}
		try {
			breakerTemplate.execute(recordSuccessScript, List.of(key));
		} catch (DataAccessException ex) {
			warnThrottled("Redis write failed; the success stays local to this instance", ex);
		} finally {
			bulkhead.release();
		}
	}

	/**
	 * @return the current state as observed by the local mirror
	 */
	@Override
	public CircuitBreaker.State getState() {
		return mirror.getState();
	}

	/**
	 * @return how many consecutive failures the local mirror currently records in CLOSED
	 */
	@Override
	public int getFailureCount() {
		return mirror.getFailureCount();
	}

	/**
	 * @return the provider this breaker protects
	 */
	@Override
	public String getProviderName() {
		return mirror.getProviderName();
	}

	private void warnThrottled(String reason, DataAccessException cause) {
		Instant now = clock.instant();
		Instant previous = lastWarn.put(providerName, now);
		if (previous == null || now.toEpochMilli() - previous.toEpochMilli() >= WARN_THROTTLE_MILLIS) {
			log.warn("Circuit breaker for provider '{}': {}", providerName, reason, cause);
		}
	}
}