package io.github.kxng0109.aegisgate.mcp.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import io.github.kxng0109.aegisgate.mcp.hitl.McpAeadResumptionTokenService;
import io.github.kxng0109.aegisgate.mcp.hitl.McpHitlSuspensionEngine;
import io.github.kxng0109.aegisgate.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.aegisgate.mcp.router.*;
import io.github.kxng0109.aegisgate.mcp.security.McpGuardrailScanner;
import io.github.kxng0109.aegisgate.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Full Coverage Branch and Edge Case Test Suite")
class McpFullCoverageBranchTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

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

	@Test
	@DisplayName("McpJsonSchemaValidator covers all parameter types, patterns, and path names")
	void mcpJsonSchemaValidatorComprehensiveBranches() throws Exception {
		McpJsonSchemaValidator validator = new McpJsonSchemaValidator();

		String schemaJson = """
				{
				  "type": "object",
				  "properties": {
				    "str": {"type": "string", "pattern": "^[a-z]+$"},
				    "num": {"type": "number", "minimum": 1.0, "maximum": 10.0},
				    "intVal": {"type": "integer", "minimum": 1, "maximum": 10},
				    "arr": {"type": "array", "minItems": 1, "maxItems": 2},
				    "boolVal": {"type": "boolean"},
				    "objVal": {"type": "object"},
				    "file_dir": {"type": "string"},
				    "uri_path": {"type": "string"}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		// Valid matching pattern
		JsonNode validArgs = objectMapper.readTree("""
				                                           {
				                                             "str": "abc",
				                                             "num": 5.5,
				                                             "intVal": 5,
				                                             "arr": ["item1"],
				                                             "boolVal": true,
				                                             "objVal": {"k": "v"},
				                                             "file_dir": "safe/path",
				                                             "uri_path": "https://example.com"
				                                           }
				                                           """);
		assertThat(validator.validate(validArgs, schema).isValid()).isTrue();

		// Safe integer underflow
		ObjectNode underflowArgs = objectMapper.createObjectNode();
		underflowArgs.put("intVal", -9007199254740992L);
		assertThat(validator.validate(underflowArgs, schema).errorMessage()).contains(
				"exceeds IEEE 754 safe integer range");

		// Non-path parameter containing ..
		ObjectNode nonPathArgs = objectMapper.createObjectNode();
		nonPathArgs.put("str", "abc");
		assertThat(validator.validate(nonPathArgs, schema).isValid()).isTrue();

		// Schema without limits on types
		JsonNode unboundedSchema = objectMapper.readTree("""
				                                                 {
				                                                   "type": "object",
				                                                   "properties": {
				                                                     "s": {"type": "string"},
				                                                     "i": {"type": "integer"},
				                                                     "n": {"type": "number"},
				                                                     "a": {"type": "array"}
				                                                   }
				                                                 }
				                                                 """);
		JsonNode unboundedArgs = objectMapper.readTree("{\"s\":\"text\",\"i\":100,\"n\":99.9,\"a\":[1,2]}");
		assertThat(validator.validate(unboundedArgs, unboundedSchema).isValid()).isTrue();
	}

	@Test
	@DisplayName("McpStreamableHttpController covers legacy SSE disabled, invalid auth headers, and missing method")
	void mcpStreamableHttpControllerEdgeBranches() {
		McpGatewayProperties properties = new McpGatewayProperties();
		McpStreamableHttpController controller = new McpStreamableHttpController(
				properties, catalogAggregator, router, rbacPolicyEngine,
				jsonSchemaValidator, guardrailScanner, hitlSuspensionEngine,
				circuitBreakerManager, keyManagementService, httpClient, objectMapper
		);

		// 1. Legacy SSE disabled
		properties.setAllowLegacySse(false);
		MockHttpServletRequest req = new MockHttpServletRequest();
		MockHttpServletResponse res = new MockHttpServletResponse();
		assertThatThrownBy(() -> controller.handleLegacySse(req, res))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Legacy SSE transport is disabled");

		// 2. Auth header resolution branches (non-bearer, blank token, invalid attr)
		properties.setAllowLegacySse(true);
		req.setAttribute("virtualApiKey", "not-a-virtual-api-key");
		req.addHeader("Authorization", "Basic user:pass");
		assertThat(controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				req
		).getStatusCode().value())
				.isEqualTo(401);

		req.removeHeader("Authorization");
		req.addHeader("Authorization", "Bearer   ");
		assertThat(controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
				null,
				null,
				req
		).getStatusCode().value())
				.isEqualTo(401);

		// 3. Request with blank method
		VirtualApiKey apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-test"),
				"gw-",
				"t1",
				"name",
				10,
				10,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
		req.setAttribute("virtualApiKey", apiKey);
		assertThat(controller.handleStreamableHttp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}", null, null, req)
		                     .getBody())
				.contains("-32600").contains("Missing method");

		// 4. Method from header when body method is empty
		assertThat(controller.handleStreamableHttp("{\"jsonrpc\":\"2.0\",\"id\":1}", null, "ping", req).getBody())
				.contains("\"result\":{}");

		// 5. Unhandled notification method
		assertThat(controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/other\"}",
				null,
				null,
				req
		).getStatusCode().value())
				.isEqualTo(202);
	}

	@Test
	@DisplayName("McpJsonRpcRequest and McpAeadResumptionTokenService cover null meta and short token branches")
	void mcpRequestAndTokenServiceEdgeBranches() {
		// McpJsonRpcRequest with null params and params without _meta
		McpJsonRpcRequest reqNoParams = new McpJsonRpcRequest("2.0", null, "ping", null);
		assertThat(reqNoParams.resolveProtocolVersion()).isNull();
		assertThat(reqNoParams.resolveProgressToken()).isNull();

		ObjectNode emptyParams = objectMapper.createObjectNode();
		McpJsonRpcRequest reqEmptyParams = new McpJsonRpcRequest("2.0", null, "ping", emptyParams);
		assertThat(reqEmptyParams.resolveProtocolVersion()).isNull();
		assertThat(reqEmptyParams.resolveProgressToken()).isNull();

		// McpAeadResumptionTokenService with short or non-aead tokens
		McpGatewayProperties props = new McpGatewayProperties();
		McpAeadResumptionTokenService tokenService = new McpAeadResumptionTokenService(props, objectMapper);
		assertThat(tokenService.verifyAndExtract("not-aead-token", "hash", "owner")).isEmpty();
		assertThat(tokenService.verifyAndExtract("v2.aead.c2hvcnQ=", "hash", "owner")).isEmpty();
		assertThat(tokenService.verifyAndExtract(null, "hash", "owner")).isEmpty();
	}

	@Test
	@DisplayName("McpToolRbacPolicyEngine and McpRouter cover null lists and empty string branches")
	void mcpRbacAndRouterEdgeBranches() {
		McpToolRbacPolicyEngine rbac = new McpToolRbacPolicyEngine();
		VirtualApiKey nullListsKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key"),
				"gw-",
				"t1",
				"k",
				10,
				10,
				null,
				null,
				null,
				null,
				true,
				Instant.now()
		);
		assertThat(rbac.isToolAllowed("any_tool", nullListsKey)).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", "")).isFalse();

		McpGatewayProperties props = new McpGatewayProperties();
		McpRouter router = new McpRouter(props);
		assertThat(McpRouter.formatNamespacedName(null, "tool")).isEqualTo("tool");
		assertThat(McpRouter.formatNamespacedName("", "tool")).isEqualTo("tool");
		assertThat(router.resolveToolRoute("")).isEmpty();
	}

	@Test
	@DisplayName("McpCatalogAggregator covers all missing JSON field permutations")
	void mcpCatalogAggregatorFieldPermutations() throws Exception {
		McpGatewayProperties props = new McpGatewayProperties();
		McpServerConfig srv = new McpServerConfig(
				"srv",
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
		props.setServers(Map.of("srv", srv));

		McpCatalogCache cache = new McpCatalogCache(props);
		HttpClient mockClient = mock(HttpClient.class);
		@SuppressWarnings("unchecked")
		HttpResponse<String> mockResp = mock(HttpResponse.class);

		when(mockResp.statusCode()).thenReturn(200);
		String toolsJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"t1\"}]}}";
		String resJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"resources\":[{\"uri\":\"u1\",\"name\":\"n1\"}]}}";
		String prmJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"prompts\":[{\"name\":\"p1\",\"arguments\":[{\"name\":\"a1\",\"required\":true}]}]}}";

		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(inv -> {
					HttpRequest req = inv.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> {
							when(mockResp.body()).thenReturn(toolsJson);
							yield mockResp;
						}
						case "resources/list" -> {
							when(mockResp.body()).thenReturn(resJson);
							yield mockResp;
						}
						case "prompts/list" -> {
							when(mockResp.body()).thenReturn(prmJson);
							yield mockResp;
						}
						default -> mockResp;
					};
				});

		McpCatalogAggregator agg = new McpCatalogAggregator(props, cache, mockClient, objectMapper);
		McpAggregatedCatalog cat = agg.refreshCatalog();

		assertThat(cat.tools()).hasSize(1);
		assertThat(cat.tools().getFirst().name()).isEqualTo("srv__t1");
		assertThat(cat.resources()).hasSize(1);
		assertThat(cat.resources().getFirst().uri()).isEqualTo("u1");
		assertThat(cat.prompts()).hasSize(1);
		assertThat(cat.prompts().getFirst().name()).isEqualTo("srv__p1");

		// Server with non-blank apiKey, allowedTools and deniedTools
		McpServerConfig srvFull = new McpServerConfig(
				"full", McpTransportType.STREAMABLE_HTTP, URI.create("http://localhost"),
				new SensitiveString("key"),
				null, null, Set.of("t_allowed"), Set.of("t_denied"), Set.of(), 10, true
		);
		props.setServers(Map.of("full", srvFull));

		String toolsFullJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"t_allowed\",\"description\":\"d\",\"_meta\":{\"v\":1}},{\"name\":\"t_denied\"}]}}";
		String resFullJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"resources\":[{\"uri\":\"u2\",\"name\":\"n2\",\"description\":\"d\",\"mimeType\":\"text/plain\",\"_meta\":{\"v\":1}}]}}";
		String prmFullJson = "{\"jsonrpc\":\"2.0\",\"result\":{\"prompts\":[{\"name\":\"p2\",\"description\":\"d\",\"arguments\":[{\"name\":\"a2\",\"description\":\"d\",\"required\":false}],\"_meta\":{\"v\":1}}]}}";

		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(inv -> {
					HttpRequest req = inv.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> {
							when(mockResp.body()).thenReturn(toolsFullJson);
							yield mockResp;
						}
						case "resources/list" -> {
							when(mockResp.body()).thenReturn(resFullJson);
							yield mockResp;
						}
						case "prompts/list" -> {
							when(mockResp.body()).thenReturn(prmFullJson);
							yield mockResp;
						}
						default -> mockResp;
					};
				});

		McpAggregatedCatalog catFull = agg.refreshCatalog();
		assertThat(catFull.tools()).hasSize(1);
		assertThat(catFull.tools().getFirst().name()).isEqualTo("full__t_allowed");
		assertThat(catFull.resources().getFirst().description()).isEqualTo("d");
		assertThat(catFull.prompts().getFirst().description()).isEqualTo("d");
	}

	@Test
	@DisplayName("McpHitlSuspensionEngine covers null arguments, empty required tools, and _meta requestState")
	void mcpHitlSuspensionEngineEdgeBranches() {
		McpGatewayProperties props = new McpGatewayProperties();
		McpAeadResumptionTokenService tokenService = new McpAeadResumptionTokenService(props, objectMapper);
		StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);

		McpHitlSuspensionEngine hitl = new McpHitlSuspensionEngine(props, tokenService, mockRedis, objectMapper);

		// Server with null/empty hitlRequiredTools
		McpServerConfig srvNoHitl = new McpServerConfig(
				"s",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				null,
				10,
				true
		);
		assertThat(hitl.isHitlRequired(srvNoHitl, "query", "s__query")).isFalse();

		VirtualApiKey apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key"),
				"gw-",
				"t1",
				"k",
				10,
				10,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
		McpJsonRpcRequest req = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1),
				"tools/call",
				null
		);
		assertThat(hitl.evaluateOrSuspend(req, srvNoHitl, "query", "s__query", apiKey)).isEmpty();

		// evaluateOrSuspend when token is expired or not approved
		McpServerConfig srvHitl = new McpServerConfig(
				"s",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of("query"),
				10,
				true
		);
		ObjectNode params = objectMapper.createObjectNode();
		params.put("requestState", "v2.aead.invalid-payload");
		McpJsonRpcRequest reqWithBadToken = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(2),
				"tools/call",
				params
		);
		assertThat(hitl.evaluateOrSuspend(reqWithBadToken, srvHitl, "query", "s__query", apiKey)).isPresent();
	}

	@Test
	@DisplayName("McpStreamableHttpController covers upstream response without content or empty response node")
	void mcpStreamableHttpControllerUpstreamEdgeCases() throws Exception {
		McpGatewayProperties properties = new McpGatewayProperties();
		McpServerConfig srv = new McpServerConfig(
				"srv",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				10,
				true
		);
		properties.setServers(Map.of("srv", srv));

		McpStreamableHttpController controller = new McpStreamableHttpController(
				properties, catalogAggregator, router, rbacPolicyEngine,
				jsonSchemaValidator, guardrailScanner, hitlSuspensionEngine,
				circuitBreakerManager, keyManagementService, httpClient, objectMapper
		);

		VirtualApiKey apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key"),
				"gw-",
				"t1",
				"k",
				10,
				10,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setAttribute("virtualApiKey", apiKey);

		McpResolvedRoute route = new McpResolvedRoute(srv, "tool", "srv__tool");
		when(router.resolveToolRoute("srv__tool")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("srv__tool", apiKey)).thenReturn(true);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(new McpAggregatedCatalog(
				List.of(),
				List.of(),
				List.of(),
				Instant.now()
		));
		when(guardrailScanner.scanArguments(any())).thenReturn(io.github.kxng0109.aegisgate.security.guardrail.secret.SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("srv")).thenReturn(true);
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

		// 1. Result without content array
		@SuppressWarnings("unchecked")
		HttpResponse<String> respNoContent = mock(HttpResponse.class);
		when(respNoContent.statusCode()).thenReturn(200);
		when(respNoContent.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"success\"}}");
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(respNoContent);

		ResponseEntity<String> resp1 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"srv__tool\"}}",
				null,
				null,
				req
		);
		assertThat(resp1.getBody()).contains("success");

		// 2. Response with empty JSON object (no result and no error)
		@SuppressWarnings("unchecked")
		HttpResponse<String> respEmpty = mock(HttpResponse.class);
		when(respEmpty.statusCode()).thenReturn(200);
		when(respEmpty.body()).thenReturn("{}");
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(respEmpty);

		ResponseEntity<String> resp2 = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"srv__tool\"}}",
				null,
				null,
				req
		);
		assertThat(resp2.getBody()).contains("-32603").contains("Upstream server returned HTTP 200");
	}
}
