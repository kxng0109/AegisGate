package io.github.kxng0109.aegisgate.mcp.resilience;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.router.McpCatalogCache;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages per-server in-memory atomic CAS circuit breakers for upstream MCP servers. Auto-prunes degraded servers from
 * the federated catalog upon state transitions.
 */
@Slf4j
@Component
public class McpServerCircuitBreakerManager {

	private final McpGatewayProperties properties;
	private final McpCatalogCache catalogCache;
	private final Clock clock;
	private final ConcurrentMap<String, ProviderCircuitBreaker> breakers = new ConcurrentHashMap<>();

	@Autowired
	public McpServerCircuitBreakerManager(McpGatewayProperties properties, McpCatalogCache catalogCache) {
		this(properties, catalogCache, Clock.systemUTC());
	}

	public McpServerCircuitBreakerManager(McpGatewayProperties properties, McpCatalogCache catalogCache, Clock clock) {
		this.properties = properties;
		this.catalogCache = catalogCache;
		this.clock = clock;
	}

	/**
	 * Resolves or initializes the circuit breaker for a named upstream MCP server.
	 */
	public ProviderCircuitBreaker getBreaker(String serverName) {
		return breakers.computeIfAbsent(
				serverName,
				name -> new ProviderCircuitBreaker(
						name,
						clock,
						properties.getCircuitBreakerFailureThreshold(),
						properties.getCircuitBreakerCooldown()
				)
		);
	}

	/**
	 * Checks if an attempt is permitted to reach the named MCP server.
	 */
	public boolean tryAcquire(String serverName) {
		return getBreaker(serverName).tryAcquire();
	}

	/**
	 * Records a successful execution against the named MCP server.
	 */
	public void recordSuccess(String serverName) {
		getBreaker(serverName).recordSuccess();
	}

	/**
	 * Records a failure; if the circuit trips to OPEN, invalidates the catalog cache to trigger auto-pruning.
	 */
	public void recordFailure(String serverName) {
		ProviderCircuitBreaker breaker = getBreaker(serverName);
		breaker.recordFailure();
		if (breaker.getState() != ProviderCircuitBreaker.State.CLOSED) {
			log.warn(
					"MCP server '{}' circuit breaker is no longer CLOSED. Triggering catalog cache invalidation.",
					serverName
			);
			catalogCache.invalidate();
		}
	}

	/**
	 * Resets the circuit breaker for the named server.
	 */
	public void reset(String serverName) {
		getBreaker(serverName).recordSuccess();
		catalogCache.invalidate();
	}
}
