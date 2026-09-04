package io.github.kxng0109.aegisgate.mcp.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import io.github.kxng0109.aegisgate.mcp.hitl.McpHitlSuspensionEngine;
import io.github.kxng0109.aegisgate.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import io.github.kxng0109.aegisgate.mcp.router.McpCatalogAggregator;
import io.github.kxng0109.aegisgate.mcp.router.McpResolvedRoute;
import io.github.kxng0109.aegisgate.mcp.router.McpRouter;
import io.github.kxng0109.aegisgate.mcp.security.McpGuardrailScanner;
import io.github.kxng0109.aegisgate.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import io.github.kxng0109.aegisgate.security.guardrail.secret.SecretScanResult;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Adversarial & Edge Case Coverage Tests")
class McpAdversarialCoverageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpGatewayProperties properties;

	@Mock
	private McpCatalogAggregator catalogAggregator;

	@Mock
	private McpRouter router;

	@Mock
	private McpToolRbacPolicyEngine rbacPolicyEngine;

	@Mock
	private McpJsonSchemaValidator jsonSchemaValidator;

	@Mock
	private McpGuardrailScanner guardrailScanner;

	@Mock
	private McpHitlSuspensionEngine hitlSuspensionEngine;

	@Mock
	private McpServerCircuitBreakerManager circuitBreakerManager;

	@Mock
	private KeyManagementService keyManagementService;

	@Mock
	private HttpClient httpClient;

	@Mock
	private HttpResponse<String> mockHttpResponse;

	private McpStreamableHttpController controller;
	private VirtualApiKey validApiKey;
	private McpServerConfig serverConfig;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		serverConfig = new McpServerConfig(
				"test_server",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"),
				new SensitiveString("key"),
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				100,
				true
		);
		properties.setServers(Map.of("test_server", serverConfig));

		validApiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-valid"),
				"gw-",
				"tenant-1",
				"key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);

		controller = new McpStreamableHttpController(
				properties,
				catalogAggregator,
				router,
				rbacPolicyEngine,
				jsonSchemaValidator,
				guardrailScanner,
				hitlSuspensionEngine,
				circuitBreakerManager,
				keyManagementService,
				httpClient,
				objectMapper
		);
	}

	@Test
	@DisplayName("tools/call handles blank name, unmapped tool, and invalid schema")
	void toolsCallValidationBranches() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		// 1. Missing tool name
		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"\"}}",
				null,
				null,
				request
		);
		assertThat(resp1.getBody()).contains("-32602").contains("Tool name is required");

		// 2. Unmapped tool
		when(router.resolveToolRoute("unknown_tool")).thenReturn(Optional.empty());
		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown_tool\"}}",
				null,
				null,
				request
		);
		assertThat(resp2.getBody()).contains("-32601").contains("Unknown or disabled MCP tool");

		// 3. Schema validation error
		McpResolvedRoute route = new McpResolvedRoute(serverConfig, "query", "test_server__query");
		when(router.resolveToolRoute("test_server__query")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("test_server__query", validApiKey)).thenReturn(true);

		McpToolDefinition toolDef = new McpToolDefinition(
				"test_server__query",
				"desc",
				objectMapper.createObjectNode(),
				null
		);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(new McpAggregatedCatalog(
				List.of(toolDef),
				List.of(),
				List.of(),
				Instant.now()
		));
		when(jsonSchemaValidator.validate(any(), any())).thenReturn(McpJsonSchemaValidator.ValidationResult.error(
				"invalid field format"));

		ResponseEntity<String> resp3 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\",\"arguments\":{\"field\":123}}}",
				null,
				null,
				request
		);
		assertThat(resp3.getBody()).contains("-32602").contains("Parameter validation error");
	}

	@Test
	@DisplayName("tools/call handles secret leakage detection and circuit breaker rejection")
	void toolsCallGuardrailAndCircuitBranches() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		McpResolvedRoute route = new McpResolvedRoute(serverConfig, "query", "test_server__query");
		when(router.resolveToolRoute("test_server__query")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("test_server__query", validApiKey)).thenReturn(true);

		McpToolDefinition toolDef = new McpToolDefinition(
				"test_server__query",
				"desc",
				objectMapper.createObjectNode(),
				null
		);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(new McpAggregatedCatalog(
				List.of(toolDef),
				List.of(),
				List.of(),
				Instant.now()
		));
		when(jsonSchemaValidator.validate(any(), any())).thenReturn(McpJsonSchemaValidator.ValidationResult.success());

		// 1. Leaked secret
		when(guardrailScanner.scanArguments(any())).thenReturn(new SecretScanResult(
				true,
				"RULE_AWS",
				"AWS key",
				"***",
				"hash",
				null
		));
		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\"}}",
				null,
				null,
				request
		);
		assertThat(resp1.getBody()).contains("-32602").contains("sensitive credential detected");

		// 2. Circuit breaker open
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("test_server")).thenReturn(false);
		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\"}}",
				null,
				null,
				request
		);
		assertThat(resp2.getBody()).contains("-32024").contains("circuit breaker open");
	}

	@Test
	@DisplayName("tools/call handles upstream HTTP 500 error, upstream JSON-RPC error, and network exceptions")
	void toolsCallUpstreamFailureBranches() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		McpResolvedRoute route = new McpResolvedRoute(serverConfig, "query", "test_server__query");
		when(router.resolveToolRoute("test_server__query")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("test_server__query", validApiKey)).thenReturn(true);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(new McpAggregatedCatalog(
				List.of(),
				List.of(),
				List.of(),
				Instant.now()
		));
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("test_server")).thenReturn(true);
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

		// 1. Upstream HTTP 500
		when(mockHttpResponse.statusCode()).thenReturn(500);
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);

		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\"}}",
				null,
				null,
				request
		);
		assertThat(resp1.getBody()).contains("-32603").contains("HTTP 500");
		verify(circuitBreakerManager).recordFailure("test_server");

		// 2. Upstream JSON-RPC error (200 OK with error object)
		when(mockHttpResponse.statusCode()).thenReturn(200);
		when(mockHttpResponse.body()).thenReturn(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"Database query syntax error\"}}");
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);

		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\"}}",
				null,
				null,
				request
		);
		assertThat(resp2.getBody()).contains("-32000").contains("Database query syntax error");

		// 3. Upstream network IOException
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException(
				"Connection reset by peer"));
		ResponseEntity<String> resp3 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"test_server__query\"}}",
				null,
				null,
				request
		);
		assertThat(resp3.getBody()).contains("-32603").contains("Connection reset by peer");
	}

	@Test
	@DisplayName("tools/call handles non-object params, non-text content output, and _meta payloads")
	void toolsCallAdditionalEdgeCases() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		// 1. Non-object params
		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":\"invalid_scalar\"}",
				null,
				null,
				request
		);
		assertThat(resp1.getBody()).contains("-32602").contains("params object required");

		// 2. Upstream result with non-text image content and _meta payload
		McpServerConfig noKeyServer = new McpServerConfig(
				"nokey",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				10,
				true
		);
		McpResolvedRoute route = new McpResolvedRoute(noKeyServer, "gen_image", "nokey__gen_image");
		when(router.resolveToolRoute("nokey__gen_image")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("nokey__gen_image", validApiKey)).thenReturn(true);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(new McpAggregatedCatalog(
				List.of(),
				List.of(),
				List.of(),
				Instant.now()
		));
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("nokey")).thenReturn(true);
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

		String upstreamImageJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"base64image\"}]}}";
		when(mockHttpResponse.statusCode()).thenReturn(200);
		when(mockHttpResponse.body()).thenReturn(upstreamImageJson);
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);

		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"nokey__gen_image\",\"_meta\":{\"progressToken\":\"p1\"}}}",
				null,
				null,
				request
		);
		assertThat(resp2.getBody()).contains("base64image");
	}
}
