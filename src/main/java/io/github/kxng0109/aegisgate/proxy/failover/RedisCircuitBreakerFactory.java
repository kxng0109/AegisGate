package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Creates and caches one {@link RedisCircuitBreaker} per provider.
 *
 * <p>All breakers share the same dedicated Redis template, Lua scripts, tunables, instance identity, clock and bulkhead
 * so the whole process behaves as one logical instance in the shared Redis breaker state.</p>
 */
public final class RedisCircuitBreakerFactory implements CircuitBreakerFactory {

	private final StringRedisTemplate breakerTemplate;
	private final DefaultRedisScript<Long> tryAcquireScript;
	private final DefaultRedisScript<Long> recordFailureScript;
	private final DefaultRedisScript<Long> recordSuccessScript;
	private final CircuitBreakerProperties props;
	private final InstanceId instanceId;
	private final GatewayProperties gatewayProperties;
	private final Clock clock;
	private final Semaphore bulkhead;

	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	/**
	 * Creates the factory and pre-populates one breaker per configured provider.
	 *
	 * @param breakerTemplate     dedicated fast Redis template with a short command timeout
	 * @param tryAcquireScript    atomic CAS script consulted on every attempt
	 * @param recordFailureScript script recording a transient failure
	 * @param recordSuccessScript script recording a success
	 * @param props               breaker tunables
	 * @param instanceId          identity of this gateway instance
	 * @param gatewayProperties   the configured providers; one breaker is created per provider key
	 * @param clock               time source for the breakers
	 * @param bulkhead            shared semaphore bounding concurrent Redis operations
	 */
	public RedisCircuitBreakerFactory(
			StringRedisTemplate breakerTemplate,
			DefaultRedisScript<Long> tryAcquireScript,
			DefaultRedisScript<Long> recordFailureScript,
			DefaultRedisScript<Long> recordSuccessScript,
			CircuitBreakerProperties props,
			InstanceId instanceId,
			GatewayProperties gatewayProperties,
			Clock clock,
			Semaphore bulkhead
	) {
		this.breakerTemplate = breakerTemplate;
		this.tryAcquireScript = tryAcquireScript;
		this.recordFailureScript = recordFailureScript;
		this.recordSuccessScript = recordSuccessScript;
		this.props = props;
		this.instanceId = instanceId;
		this.gatewayProperties = gatewayProperties;
		this.clock = clock;
		this.bulkhead = bulkhead;
		for (String name : gatewayProperties.getProviders().keySet()) {
			breakers.put(name, createBreaker(name));
		}
	}

	/**
	 * @param providerName provider whose breaker is wanted
	 * @return the cached breaker for the provider, creating it lazily when absent
	 */
	@Override
	public CircuitBreaker get(String providerName) {
		return breakers.computeIfAbsent(providerName, this::createBreaker);
	}

	/**
	 * @return the set of configured provider names (never null)
	 */
	@Override
	public Set<String> providerNames() {
		return gatewayProperties.getProviders().keySet();
	}

	/**
	 * Clears the local breaker cache and best-effort deletes the Redis breaker key of every known provider.
	 *
	 * <p>Deleting the Redis keys is best-effort on purpose: when Redis is down the local cache is already cleared, and
	 * the next successful script read recreates the keys on demand.</p>
	 */
	@Override
	public void reset() {
		Set<String> names = new HashSet<>(breakers.keySet());
		names.addAll(gatewayProperties.getProviders().keySet());
		breakers.clear();
		for (String name : names) {
			try {
				breakerTemplate.delete(RedisCircuitBreaker.KEY_PREFIX + name);
			} catch (DataAccessException ignored) {
				// Best-effort cleanup; the next successful script read recreates the key on demand.
			}
		}
	}

	/**
	 * Clears the local breaker cache for the specific provider and best-effort deletes its Redis breaker key.
	 *
	 * @param providerName provider whose breaker is to be reset
	 * @return the new state of the provider
	 */
	@Override
	public CircuitBreaker.State reset(String providerName) {
		breakers.remove(providerName);
		try {
			breakerTemplate.delete(RedisCircuitBreaker.KEY_PREFIX + providerName);
		} catch (DataAccessException ignored) {
			// Best-effort cleanup; the next successful script read recreates the key on demand.
		}
		return get(providerName).getState();
	}

	/**
	 * @return a snapshot of each configured provider's state for metrics (never null)
	 */
	@Override
	public Map<String, CircuitBreaker.State> states() {
		Map<String, CircuitBreaker.State> states = new HashMap<>();
		for (String name : providerNames()) {
			states.put(name, get(name).getState());
		}
		return states;
	}

	private CircuitBreaker createBreaker(String providerName) {
		return new RedisCircuitBreaker(
				providerName,
				breakerTemplate,
				tryAcquireScript,
				recordFailureScript,
				recordSuccessScript,
				props,
				instanceId.value(),
				clock,
				bulkhead
		);
	}
}