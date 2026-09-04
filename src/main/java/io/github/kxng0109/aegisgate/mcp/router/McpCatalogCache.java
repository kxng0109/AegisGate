package io.github.kxng0109.aegisgate.mcp.router;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * High-performance L0 in-memory W-TinyLFU cache for the federated Model Context Protocol catalog. Provides sub-20ns
 * read latency and instant push invalidation.
 */
@Slf4j
@Component
public class McpCatalogCache {

	private static final String CATALOG_KEY = "MCP_GLOBAL_CATALOG";

	private final Cache<String, McpAggregatedCatalog> cache;

	public McpCatalogCache(McpGatewayProperties properties) {
		this.cache = Caffeine.newBuilder()
		                     .maximumSize(16)
		                     .expireAfterWrite(properties.getCatalogCacheTtl())
		                     .recordStats()
		                     .build();
	}

	/**
	 * Retrieves the cached catalog or computes it atomically if absent.
	 *
	 * @param loader catalog computation supplier
	 * @return aggregated catalog
	 */
	public McpAggregatedCatalog getOrCompute(Supplier<McpAggregatedCatalog> loader) {
		return cache.get(CATALOG_KEY, k -> loader.get());
	}

	/**
	 * Retrieves the cached catalog if present without computing.
	 */
	public Optional<McpAggregatedCatalog> getIfPresent() {
		return Optional.ofNullable(cache.getIfPresent(CATALOG_KEY));
	}

	/**
	 * Directly updates the cached catalog.
	 */
	public void put(McpAggregatedCatalog catalog) {
		cache.put(CATALOG_KEY, catalog);
	}

	/**
	 * Invalidates the cached catalog, forcing the next lookup to re-federate from upstream servers.
	 */
	public void invalidate() {
		log.info("Invalidating L0 MCP catalog cache");
		cache.invalidate(CATALOG_KEY);
	}
}
