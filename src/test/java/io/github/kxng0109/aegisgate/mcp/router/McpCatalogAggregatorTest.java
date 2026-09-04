package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Catalog Aggregator Unit Tests")
class McpCatalogAggregatorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpGatewayProperties properties;
	private McpCatalogCache catalogCache;

	@Mock
	private HttpClient httpClient;

	@Mock
	private HttpResponse<String> pgToolsResponse;

	@Mock
	private HttpResponse<String> pgResourcesResponse;

	@Mock
	private HttpResponse<String> pgPromptsResponse;

	private McpCatalogAggregator aggregator;
	private McpServerConfig postgresServer;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		catalogCache = new McpCatalogCache(properties);

		postgresServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				new SensitiveString("pg-key"),
				null,
				null,
				Set.of("run_query", "permitted_tool"),
				Set.of("denied_tool"),
				Set.of(),
				100,
				true
		);
		properties.setServers(Map.of("postgres", postgresServer));

		aggregator = new McpCatalogAggregator(properties, catalogCache, httpClient, objectMapper);
	}

	@Test
	@DisplayName("refreshCatalog aggregates tools, resources, and prompts across upstream servers")
	void refreshCatalogSuccess() throws Exception {
		String toolsJson = """
				{
				  "jsonrpc": "2.0",
				  "id": "cat-tools",
				  "result": {
				    "tools": [
				      {"name": "run_query", "description": "Execute SQL query", "inputSchema": {"type": "object"}},
				      {"name": "denied_tool", "description": "Denied by server config"},
				      {"name": "unallowed_tool", "description": "Not in allowedTools whitelist"}
				    ]
				  }
				}
				""";

		String resourcesJson = """
				{
				  "jsonrpc": "2.0",
				  "id": "cat-res",
				  "result": {
				    "resources": [
				      {"uri": "postgres://table/users", "name": "Users", "mimeType": "application/json"}
				    ]
				  }
				}
				""";

		String promptsJson = """
				{
				  "jsonrpc": "2.0",
				  "id": "cat-prm",
				  "result": {
				    "prompts": [
				      {"name": "explain_query", "description": "Explain SQL query", "arguments": [{"name": "sql", "required": true}]}
				    ]
				  }
				}
				""";

		when(pgToolsResponse.statusCode()).thenReturn(200);
		when(pgToolsResponse.body()).thenReturn(toolsJson);

		when(pgResourcesResponse.statusCode()).thenReturn(200);
		when(pgResourcesResponse.body()).thenReturn(resourcesJson);

		when(pgPromptsResponse.statusCode()).thenReturn(200);
		when(pgPromptsResponse.body()).thenReturn(promptsJson);

		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(invocation -> {
					HttpRequest req = invocation.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> pgToolsResponse;
						case "resources/list" -> pgResourcesResponse;
						case "prompts/list" -> pgPromptsResponse;
						default -> pgToolsResponse;
					};
				});

		McpAggregatedCatalog catalog = aggregator.refreshCatalog();

		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.tools().getFirst().name()).isEqualTo("postgres__run_query");
		assertThat(catalog.tools().getFirst().description()).isEqualTo("Execute SQL query");

		assertThat(catalog.resources()).hasSize(1);
		assertThat(catalog.resources().getFirst().uri()).isEqualTo("postgres://table/users");

		assertThat(catalog.prompts()).hasSize(1);
		assertThat(catalog.prompts().getFirst().name()).isEqualTo("postgres__explain_query");
		assertThat(catalog.prompts().getFirst().arguments()).hasSize(1);
		assertThat(catalog.prompts().getFirst().arguments().getFirst().name()).isEqualTo("sql");
	}

	@Test
	@DisplayName("refreshCatalog returns empty catalog when no servers are configured")
	void refreshCatalogEmptyServers() {
		properties.setServers(Map.of());
		McpAggregatedCatalog catalog = aggregator.refreshCatalog();
		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.resources()).isEmpty();
		assertThat(catalog.prompts()).isEmpty();
	}

	@Test
	@DisplayName("refreshCatalog gracefully handles upstream 500 errors and network exceptions")
	void refreshCatalogUpstreamErrors() throws Exception {
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new RuntimeException("Upstream timeout"));

		McpAggregatedCatalog catalog = aggregator.refreshCatalog();
		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.resources()).isEmpty();
		assertThat(catalog.prompts()).isEmpty();
	}

	@Test
	@DisplayName("refreshCatalog gracefully handles non-200 responses and malformed JSON arrays")
	void refreshCatalogMalformedResponses() throws Exception {
		// Server without API key
		McpServerConfig noKeyServer = new McpServerConfig(
				"nokey",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8089"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				10,
				true
		);
		properties.setServers(Map.of("nokey", noKeyServer));

		when(pgToolsResponse.statusCode()).thenReturn(200);
		when(pgToolsResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"\"},{\"name\":\"valid_tool\",\"description\":\"desc\",\"_meta\":{\"k\":\"v\"}}]}}");
		when(pgResourcesResponse.statusCode()).thenReturn(200);
		when(pgResourcesResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"result\":{\"resources\":[{\"uri\":\"u1\",\"name\":\"n1\",\"description\":\"d\",\"mimeType\":\"text/plain\",\"_meta\":{\"k\":\"v\"}}]}}");
		when(pgPromptsResponse.statusCode()).thenReturn(200);
		when(pgPromptsResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"result\":{\"prompts\":[{\"name\":\"p1\",\"description\":\"d\",\"arguments\":[{\"name\":\"a1\",\"description\":\"d\",\"required\":true}],\"_meta\":{\"k\":\"v\"}}]}}");

		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(invocation -> {
					HttpRequest req = invocation.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> pgToolsResponse;
						case "resources/list" -> pgResourcesResponse;
						case "prompts/list" -> pgPromptsResponse;
						default -> pgToolsResponse;
					};
				});

		McpAggregatedCatalog catalog = aggregator.refreshCatalog();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.tools().getFirst().name()).isEqualTo("nokey__valid_tool");
		assertThat(catalog.resources()).hasSize(1);
		assertThat(catalog.prompts()).hasSize(1);
	}

	@Test
	@DisplayName("refreshCatalog skips disabled servers and handles null/empty sub-lists")
	void refreshCatalogDisabledServerAndEmptySublists() throws Exception {
		McpServerConfig disabledServer = new McpServerConfig(
				"dis",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://dis"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				10,
				false
		);
		properties.setServers(Map.of("dis", disabledServer, "postgres", postgresServer));

		when(pgToolsResponse.statusCode()).thenReturn(200);
		when(pgToolsResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"run_query\"}]}}");
		when(pgResourcesResponse.statusCode()).thenReturn(200);
		when(pgResourcesResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"result\":{\"resources\":[{\"uri\":\"u1\"}]}}");
		when(pgPromptsResponse.statusCode()).thenReturn(200);
		when(pgPromptsResponse.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"result\":{\"prompts\":[{\"name\":\"p1\"}]}}");

		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(inv -> {
					HttpRequest req = inv.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> pgToolsResponse;
						case "resources/list" -> pgResourcesResponse;
						case "prompts/list" -> pgPromptsResponse;
						default -> pgToolsResponse;
					};
				});

		McpAggregatedCatalog catalog = aggregator.refreshCatalog();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.tools().getFirst().name()).isEqualTo("postgres__run_query");
	}

	@Test
	@DisplayName("scheduledRefresh catches exceptions from catalog computation gracefully")
	void scheduledRefreshCatchesExceptions() {
		McpCatalogCache failingCache = mock(McpCatalogCache.class);
		doThrow(new RuntimeException("Cache eviction error")).when(failingCache).put(any());

		McpCatalogAggregator failAgg = new McpCatalogAggregator(properties, failingCache, httpClient, objectMapper);
		failAgg.scheduledRefresh(); // Must not throw!
	}
}
