package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Catalog Cache Unit Tests")
class McpCatalogCacheTest {

	private McpGatewayProperties properties;
	private McpCatalogCache catalogCache;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		catalogCache = new McpCatalogCache(properties);
	}

	@Test
	@DisplayName("getOrCompute caches value and does not invoke loader on subsequent calls")
	void getOrComputeCachesResult() {
		AtomicInteger loadCount = new AtomicInteger();
		McpAggregatedCatalog catalog1 = new McpAggregatedCatalog(
				List.of(new McpToolDefinition("pg__query", "desc", null, null)),
				List.of(),
				List.of(),
				Instant.now()
		);

		McpAggregatedCatalog result1 = catalogCache.getOrCompute(() -> {
			loadCount.incrementAndGet();
			return catalog1;
		});

		McpAggregatedCatalog result2 = catalogCache.getOrCompute(() -> {
			loadCount.incrementAndGet();
			return McpAggregatedCatalog.empty();
		});

		assertThat(loadCount.get()).isEqualTo(1);
		assertThat(result1).isEqualTo(catalog1);
		assertThat(result2).isEqualTo(catalog1);
	}

	@Test
	@DisplayName("getIfPresent, put, and invalidate manipulate cache state correctly")
	void cacheDirectMutations() {
		assertThat(catalogCache.getIfPresent()).isEmpty();

		McpAggregatedCatalog catalog = new McpAggregatedCatalog(List.of(), List.of(), List.of(), Instant.now());
		catalogCache.put(catalog);

		Optional<McpAggregatedCatalog> present = catalogCache.getIfPresent();
		assertThat(present).isPresent();
		assertThat(present.get()).isEqualTo(catalog);

		catalogCache.invalidate();
		assertThat(catalogCache.getIfPresent()).isEmpty();
	}
}
