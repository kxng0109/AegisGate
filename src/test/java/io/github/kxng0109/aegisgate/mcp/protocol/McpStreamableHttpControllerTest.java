package io.github.kxng0109.aegisgate.mcp.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Streamable HTTP & SSE Controller Unit Tests")
class McpStreamableHttpControllerTest {

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
	private McpServerConfig postgresServer;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		postgresServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				new SensitiveString("pg-secret"),
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				100,
				true
		);
		properties.setServers(Map.of("postgres", postgresServer));

		validApiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-test-key-1234567890abcdef"),
				"gw-",
				"tenant-1",
				"test-key",
				100,
				100000,
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
	@DisplayName("Returns 503 when MCP Gateway subsystem is disabled")
	void returnsServiceUnavailableWhenDisabled() {
		properties.setEnabled(false);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).contains("-32000").contains("MCP Gateway is disabled");
	}

	@Test
	@DisplayName("Returns 401 when Virtual API key is missing or disabled")
	void returnsUnauthorizedWhenKeyMissingOrDisabled() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		// Missing key
		ResponseEntity<String> response1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				request
		);
		assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// Disabled key
		VirtualApiKey disabledKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-disabled-key"),
				"gw-",
				"tenant-1",
				"disabled",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				false,
				Instant.now()
		);
		request.setAttribute("virtualApiKey", disabledKey);
		ResponseEntity<String> response2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				request
		);
		assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("Resolves Virtual API Key via Authorization Bearer header if attribute absent")
	void resolvesApiKeyFromAuthorizationBearerHeader() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer gw-header-key-1234567890abcdef");
		when(keyManagementService.findByHash(any())).thenReturn(Optional.of(validApiKey));

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("Returns 400 with -32022 when protocol version is unsupported")
	void returnsUnsupportedProtocolVersion() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				"2023-01-01",
				null,
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("-32022").contains("Unsupported protocol version");
	}

	@Test
	@DisplayName("Returns 400 with -32700 on malformed JSON body")
	void returnsParseErrorOnMalformedJson() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\", malformed...",
				null,
				null,
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("-32700").contains("Parse error");
	}

	@Test
	@DisplayName("Returns 202 Accepted on notifications")
	void handlesNotificationsGracefully() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		// notifications/initialized
		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
				null,
				null,
				request
		);
		assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

		// notifications/tools/list_changed
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(McpAggregatedCatalog.empty());
		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}",
				null,
				null,
				request
		);
		assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

	@Test
	@DisplayName("Handles ping and initialize methods successfully")
	void handlesPingAndInitialize() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		// ping
		ResponseEntity<String> pingResp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"p-1\",\"method\":\"ping\"}",
				null,
				null,
				request
		);
		assertThat(pingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(pingResp.getBody()).contains("\"result\":{}");

		// initialize
		ResponseEntity<String> initResp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"init-1\",\"method\":\"initialize\"}",
				"2026-07-28",
				null,
				request
		);
		assertThat(initResp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(initResp.getBody())
				.contains("AegisGate-MCP-Gateway")
				.contains("1.4.0")
				.contains("2026-07-28")
				.contains("tools");

		// Unknown method -> -32601 Method Not Found
		ResponseEntity<String> unknownResp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"u-1\",\"method\":\"unknown/method\"}",
				null,
				null,
				request
		);
		assertThat(unknownResp.getBody()).contains("-32601").contains("Method not found");
	}

	@Test
	@DisplayName("tools/list prunes tools from tripped circuit breakers and returns filtered catalog")
	void handlesToolsListWithCircuitPruning() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		McpToolDefinition tool1 = new McpToolDefinition("postgres__query", "Query DB", null, null);
		McpToolDefinition tool2 = new McpToolDefinition("offline__search", "Search DB", null, null);
		McpAggregatedCatalog catalog = new McpAggregatedCatalog(
				List.of(tool1, tool2),
				List.of(),
				List.of(),
				Instant.now()
		);

		when(catalogAggregator.getAggregatedCatalog()).thenReturn(catalog);
		when(rbacPolicyEngine.filterCatalog(catalog, validApiKey)).thenReturn(catalog);

		// postgres is healthy, offline is tripped
		McpResolvedRoute route1 = new McpResolvedRoute(postgresServer, "query", "postgres__query");
		when(router.resolveToolRoute("postgres__query")).thenReturn(Optional.of(route1));
		when(circuitBreakerManager.tryAcquire("postgres")).thenReturn(true);

		McpServerConfig offlineServer = new McpServerConfig(
				"offline",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://offline"),
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				true
		);
		McpResolvedRoute route2 = new McpResolvedRoute(offlineServer, "search", "offline__search");
		when(router.resolveToolRoute("offline__search")).thenReturn(Optional.of(route2));
		when(circuitBreakerManager.tryAcquire("offline")).thenReturn(false);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"tl-1\",\"method\":\"tools/list\"}",
				null,
				null,
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("postgres__query").doesNotContain("offline__search");
	}

	@Test
	@DisplayName("resources/list and prompts/list return aggregated definitions")
	void handlesResourcesAndPromptsList() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		McpResourceDefinition res = new McpResourceDefinition(
				"postgres://table",
				"Table",
				"Desc",
				"application/json",
				null
		);
		McpPromptDefinition prompt = new McpPromptDefinition("review_code", "Review code prompt", List.of(), null);
		McpAggregatedCatalog catalog = new McpAggregatedCatalog(
				List.of(),
				List.of(res),
				List.of(prompt),
				Instant.now()
		);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(catalog);

		// resources/list
		ResponseEntity<String> resResp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"r-1\",\"method\":\"resources/list\"}",
				null,
				null,
				request
		);
		assertThat(resResp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resResp.getBody()).contains("postgres://table");

		// prompts/list
		ResponseEntity<String> prmResp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":\"p-1\",\"method\":\"prompts/list\"}",
				null,
				null,
				request
		);
		assertThat(prmResp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(prmResp.getBody()).contains("review_code");
	}

	@Test
	@DisplayName("tools/call executes full pipeline: routing, RBAC, schema, guardrails, HITL, HTTP/2 dispatch and nonced tag wrapping")
	void handlesToolsCallFullPipeline() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		String rawRpc = """
				{
				  "jsonrpc": "2.0",
				  "id": "tc-101",
				  "method": "tools/call",
				  "params": {
				    "name": "postgres__run_query",
				    "arguments": {"sql": "SELECT 1"}
				  }
				}
				""";

		McpResolvedRoute route = new McpResolvedRoute(postgresServer, "run_query", "postgres__run_query");
		when(router.resolveToolRoute("postgres__run_query")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("postgres__run_query", validApiKey)).thenReturn(true);
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("postgres")).thenReturn(true);
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

		McpToolDefinition toolDef = new McpToolDefinition(
				"postgres__run_query",
				"Query DB",
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

		// Upstream mock response
		String upstreamJson = """
				{
				  "jsonrpc": "2.0",
				  "id": "tc-101",
				  "result": {
				    "content": [
				      {"type": "text", "text": "[{\\"count\\": 1}]"}
				    ]
				  }
				}
				""";
		when(mockHttpResponse.statusCode()).thenReturn(200);
		when(mockHttpResponse.body()).thenReturn(upstreamJson);
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);
		when(guardrailScanner.wrapToolOutputWithNonce(eq("postgres__run_query"), eq("[{\"count\": 1}]")))
				.thenReturn(
						"<tool_result name=\"postgres__run_query\" nonce=\"abc12345\">[{\"count\": 1}]</tool_result>");

		ResponseEntity<String> response = controller.handleStreamableHttp(rawRpc, null, null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody())
				.contains("<tool_result name=\\\"postgres__run_query\\\" nonce=\\\"abc12345\\\">")
				.contains("tc-101");
		verify(circuitBreakerManager).recordSuccess("postgres");

		// tools/call with _meta
		String rpcWithMeta = """
				{
				  "jsonrpc": "2.0",
				  "id": "tc-meta",
				  "method": "tools/call",
				  "params": {
				    "name": "postgres__run_query",
				    "_meta": {"progressToken": "p1"}
				  }
				}
				""";
		ResponseEntity<String> respMeta = controller.handleStreamableHttp(rpcWithMeta, null, null, request);
		assertThat(respMeta.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("tools/call fails closed with -32025 on RBAC permission denial")
	void handlesToolsCallRbacDenial() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);

		String rawRpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"postgres__drop_db\"}}";
		McpResolvedRoute route = new McpResolvedRoute(postgresServer, "drop_db", "postgres__drop_db");
		when(router.resolveToolRoute("postgres__drop_db")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("postgres__drop_db", validApiKey)).thenReturn(false);

		ResponseEntity<String> response = controller.handleStreamableHttp(rawRpc, null, null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("-32025").contains("prohibited by security policy");
	}

	@Test
	@DisplayName("Legacy SSE stream and legacy message endpoint operate correctly")
	void legacySseAndMessageEndpoints() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		request.setAttribute("virtualApiKey", validApiKey);

		// GET /v1/mcp/sse
		controller.handleLegacySse(request, response);
		assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");

		// POST /v1/mcp/message
		ResponseEntity<String> msgResp = controller.handleLegacyMessage(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				request
		);
		assertThat(msgResp.getStatusCode()).isEqualTo(HttpStatus.OK);
	}
}
