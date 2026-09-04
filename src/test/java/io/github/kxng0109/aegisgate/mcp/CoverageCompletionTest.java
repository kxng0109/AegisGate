package io.github.kxng0109.aegisgate.mcp;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.*;
import io.github.kxng0109.aegisgate.mcp.hitl.McpAeadResumptionTokenService;
import io.github.kxng0109.aegisgate.mcp.hitl.McpHitlSuspensionEngine;
import io.github.kxng0109.aegisgate.mcp.protocol.McpHeaderNormalizer;
import io.github.kxng0109.aegisgate.mcp.protocol.McpStreamableHttpController;
import io.github.kxng0109.aegisgate.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.aegisgate.mcp.router.*;
import io.github.kxng0109.aegisgate.mcp.security.McpGuardrailScanner;
import io.github.kxng0109.aegisgate.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import io.github.kxng0109.aegisgate.proxy.protocol.AnthropicAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.DeepSeekSseNormalizer;
import io.github.kxng0109.aegisgate.proxy.protocol.GeminiAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.GeminiSseNormalizer;
import io.github.kxng0109.aegisgate.security.guardrail.secret.IngressSecretScanner;
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
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Exhaustive branch-completion suite covering the remaining unexercised decision points across the MCP gateway, catalog
 * federation, HITL engine, schema validator, guardrail scanner, and Gemini protocol adapters.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Coverage Completion Tests")
class CoverageCompletionTest {

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

	private McpGatewayProperties properties;
	private McpStreamableHttpController controller;
	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-cov-key"), "gw-", "tenant-cov", "cov", 100, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), true, Instant.now()
		);
		controller = new McpStreamableHttpController(
				properties, catalogAggregator, router, rbacPolicyEngine,
				jsonSchemaValidator, guardrailScanner, hitlSuspensionEngine,
				circuitBreakerManager, keyManagementService, httpClient, objectMapper
		);
	}

	private MockHttpServletRequest keyedRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", apiKey);
		return request;
	}

	@Test
	@DisplayName("tools/call with null params is rejected with invalid params error")
	void toolsCallNullParams() {
		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":null}",
				null,
				null,
				keyedRequest()
		);
		assertThat(response.getBody()).contains("-32602").contains("params object required");
	}

	@Test
	@DisplayName("tools/call returns HITL suspension response when engine suspends execution")
	void toolsCallHitlSuspension() throws Exception {
		properties.setServers(Map.of("srv", serverConfig("srv")));
		McpResolvedRoute route = new McpResolvedRoute(serverConfig("srv"), "priv", "srv__priv");
		when(router.resolveToolRoute("srv__priv")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("srv__priv", apiKey)).thenReturn(true);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(McpAggregatedCatalog.empty());
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("srv")).thenReturn(true);

		ObjectNode approval = objectMapper.createObjectNode().put("resultType", "input_required");
		approval.put("requestState", "v2.aead.sample");
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(McpJsonRpcResponse.success(
						objectMapper.getNodeFactory().numberNode(1),
						approval
				)));

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"srv__priv\"}}",
				null,
				null,
				keyedRequest()
		);
		assertThat(response.getBody()).contains("input_required").contains("requestState");
		verify(httpClient, never()).send(any(), any());
	}

	@Test
	@DisplayName("tools/call handles non-array content and text items without a text key")
	void toolsCallContentEdgeCases() throws Exception {
		Map<String, Object> upstreamResponses = Map.<String, Object>of(
				"non-array",
				mockUpstream(200, "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":\"scalar\"}}"),
				"text-no-key",
				mockUpstream(200, "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\"}]}}"),
				"empty-key",
				mockUpstream(200, "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}")
		);

		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(inv -> {
					HttpRequest req = inv.getArgument(0);
					String name = req.headers().firstValue("Mcp-Name").orElse("tool");
					return upstreamResponses.get(name);
				});

		for (Map.Entry<String, Object> entry : upstreamResponses.entrySet()) {
			String tool = (String) entry.getKey();
			McpServerConfig server = serverConfig("srv");
			properties.setServers(Map.of("srv", server));
			McpResolvedRoute route = new McpResolvedRoute(server, tool, "srv__" + tool);
			when(router.resolveToolRoute("srv__" + tool)).thenReturn(Optional.of(route));
			when(rbacPolicyEngine.isToolAllowed("srv__" + tool, apiKey)).thenReturn(true);
			when(catalogAggregator.getAggregatedCatalog()).thenReturn(McpAggregatedCatalog.empty());
			when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
			when(circuitBreakerManager.tryAcquire("srv")).thenReturn(true);
			when(hitlSuspensionEngine.evaluateOrSuspend(
					any(),
					any(),
					any(),
					any(),
					any()
			)).thenReturn(Optional.empty());

			ResponseEntity<String> response = controller.handleStreamableHttp(
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"srv__" + tool
							+ "\"}}",
					null,
					null,
					keyedRequest()
			);
			assertThat(response.getBody()).doesNotContain("-32603");
		}
	}

	@Test
	@DisplayName("tools/call with blank-value server apiKey executes without Authorization header")
	void toolsCallBlankApiKey() throws Exception {
		McpServerConfig server = new McpServerConfig(
				"srv", McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"), new SensitiveString(""), null, null,
				Set.of(), Set.of(), Set.of(), 10, true
		);
		properties.setServers(Map.of("srv", server));

		McpResolvedRoute route = new McpResolvedRoute(server, "tool", "srv__tool");
		when(router.resolveToolRoute("srv__tool")).thenReturn(Optional.of(route));
		when(rbacPolicyEngine.isToolAllowed("srv__tool", apiKey)).thenReturn(true);
		when(catalogAggregator.getAggregatedCatalog()).thenReturn(McpAggregatedCatalog.empty());
		when(guardrailScanner.scanArguments(any())).thenReturn(SecretScanResult.clean());
		when(circuitBreakerManager.tryAcquire("srv")).thenReturn(true);
		when(hitlSuspensionEngine.evaluateOrSuspend(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

		HttpResponse<String> upstream = mockUpstream(200, "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"ok\"}}");
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(upstream);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"srv__tool\"}}",
				null,
				null,
				keyedRequest()
		);
		assertThat(response.getBody()).contains("ok");
	}

	@Test
	@DisplayName("Catalog aggregator executes getAggregatedCatalog and handles non-2xx and non-array results")
	void catalogAggregatorBranchCompletion() throws Exception {
		McpCatalogCache cache = new McpCatalogCache(properties);
		McpServerConfig server = new McpServerConfig(
				"srv", McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"), null, null, null,
				Set.of(), Set.of(), Set.of(), 10, true
		);
		properties.setServers(Map.of("srv", server));

		HttpClient mockClient = mock(HttpClient.class);
		McpCatalogAggregator aggregator = new McpCatalogAggregator(properties, cache, mockClient, objectMapper);

		McpAggregatedCatalog fromCache = aggregator.getAggregatedCatalog();
		assertThat(fromCache).isNotNull();

		Map<String, String> responses = Map.of(
				"tools/list", "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":\"not-array\"}}",
				"resources/list", "{\"jsonrpc\":\"2.0\",\"result\":{\"resources\":\"not-array\"}}",
				"prompts/list", "{\"jsonrpc\":\"2.0\",\"result\":{\"prompts\":\"not-array\"}}"
		);
		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(inv -> {
					HttpRequest req = inv.getArgument(0);
					String method = req.headers().firstValue("Mcp-Method").orElse("");
					return switch (method) {
						case "tools/list" -> mockUpstream(200, responses.get("tools/list"));
						case "resources/list" -> mockUpstream(200, responses.get("resources/list"));
						case "prompts/list" -> mockUpstream(200, responses.get("prompts/list"));
						default -> mockUpstream(500, "{}");
					};
				});

		cache.invalidate();
		McpAggregatedCatalog nonArray = aggregator.refreshCatalog();
		assertThat(nonArray.tools()).isEmpty();
		assertThat(nonArray.resources()).isEmpty();
		assertThat(nonArray.prompts()).isEmpty();

		HttpResponse<String> serverError = mockUpstream(500, "{}");
		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(serverError);
		cache.invalidate();
		McpAggregatedCatalog errorCatalog = aggregator.refreshCatalog();
		assertThat(errorCatalog.tools()).isEmpty();
		assertThat(errorCatalog.resources()).isEmpty();
		assertThat(errorCatalog.prompts()).isEmpty();
	}

	@Test
	@DisplayName("Catalog aggregator covers empty allow/deny tool lists and malformed upstream JSON")
	void catalogAggregatorEmptyListsAndMalformedJson() throws Exception {
		McpCatalogCache cache = new McpCatalogCache(properties);
		McpServerConfig server = new McpServerConfig(
				"srv", McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"), new SensitiveString(""), null, null,
				Set.of(), Set.of(), Set.of(), 10, true
		);
		properties.setServers(Map.of("srv", server));

		HttpClient mockClient = mock(HttpClient.class);
		McpCatalogAggregator aggregator = new McpCatalogAggregator(properties, cache, mockClient, objectMapper);

		HttpResponse<String> malformed = mockUpstream(200, "{not-valid-json");
		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(malformed);
		cache.invalidate();
		McpAggregatedCatalog malformedCatalog = aggregator.refreshCatalog();
		assertThat(malformedCatalog.tools()).isEmpty();
		assertThat(malformedCatalog.resources()).isEmpty();
		assertThat(malformedCatalog.prompts()).isEmpty();

		String itemsJson = """
				{
				  "jsonrpc": "2.0",
				  "result": {
				    "tools": [{"name": "t1"}],
				    "resources": [{"uri": "u1"}],
				    "prompts": [{"name": "p1"}]
				  }
				}
				""";
		HttpResponse<String> ok = mockUpstream(200, itemsJson);
		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(ok);
		cache.invalidate();
		McpAggregatedCatalog itemCatalog = aggregator.refreshCatalog();
		assertThat(itemCatalog.tools()).hasSize(1);
		assertThat(itemCatalog.tools().getFirst().name()).isEqualTo("srv__t1");
		assertThat(itemCatalog.resources()).hasSize(1);
		assertThat(itemCatalog.prompts()).hasSize(1);
	}

	@Test
	@DisplayName("tools/list prunes tools whose routes cannot be resolved")
	void toolsListPrunesUnroutableTools() {
		properties.setServers(Map.of("srv", serverConfig("srv")));
		McpToolDefinition routable = new McpToolDefinition("srv__ok", "desc", null, null);
		McpToolDefinition unroutable = new McpToolDefinition("gone__missing", "desc", null, null);
		McpAggregatedCatalog catalog = new McpAggregatedCatalog(
				List.of(routable, unroutable), List.of(), List.of(), Instant.now());

		when(catalogAggregator.getAggregatedCatalog()).thenReturn(catalog);
		when(rbacPolicyEngine.filterCatalog(catalog, apiKey)).thenReturn(catalog);
		when(router.resolveToolRoute("srv__ok"))
				.thenReturn(Optional.of(new McpResolvedRoute(serverConfig("srv"), "ok", "srv__ok")));
		when(router.resolveToolRoute("gone__missing")).thenReturn(Optional.empty());
		when(circuitBreakerManager.tryAcquire("srv")).thenReturn(true);

		ResponseEntity<String> response = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
				null,
				null,
				keyedRequest()
		);
		// Route unresolvable tools pass through (only breaker-tripped servers are pruned)
		assertThat(response.getBody()).contains("srv__ok").contains("gone__missing");
	}

	@Test
	@DisplayName("HITL engine handles null params and non-textual _meta requestState when tool is privileged")
	void hitlNullParamsAndMetaEdgeCases() {
		McpServerConfig hitlServer = new McpServerConfig(
				"s", McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost"), null, null, null,
				Set.of(), Set.of(), Set.of("priv"), 10, true
		);

		McpGatewayProperties props = new McpGatewayProperties();
		McpAeadResumptionTokenService tokenService = new McpAeadResumptionTokenService(props, objectMapper);
		org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
		McpHitlSuspensionEngine hitl = new McpHitlSuspensionEngine(props, tokenService, redis, objectMapper);

		// params null -> extractResumptionToken null-branch
		McpJsonRpcRequest nullParams = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1), "tools/call", null
		);
		assertThat(hitl.evaluateOrSuspend(nullParams, hitlServer, "priv", "s__priv", apiKey)).isPresent();

		// _meta.requestState non-textual -> falls through to re-suspension
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "s__priv");
		params.putObject("_meta").put("requestState", 123);
		McpJsonRpcRequest nonTextualMeta = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(2), "tools/call", params
		);
		assertThat(hitl.evaluateOrSuspend(nonTextualMeta, hitlServer, "priv", "s__priv", apiKey)).isPresent();
	}

	@Test
	@DisplayName("JSON Schema validator covers null-valued required fields, non-boolean additionalProperties, and dir paths")
	void jsonSchemaValidatorBranchCompletion() throws Exception {
		McpJsonSchemaValidator validator = new McpJsonSchemaValidator();

		JsonNode requiredSchema = objectMapper.readTree("""
				                                                {"type":"object","required":["limit"],"properties":{"limit":{"type":"integer"}}}
				                                                """);
		assertThat(validator.validate(objectMapper.readTree("{\"limit\":null}"), requiredSchema).errorMessage())
				.contains("Missing required parameter: 'limit'");

		JsonNode nonBoolAddProps = objectMapper.readTree("""
				                                                 {"type":"object","additionalProperties":"yes","properties":{"a":{"type":"string"}}}
				                                                 """);
		assertThat(validator.validate(objectMapper.readTree("{\"a\":\"v\",\"extra\":1}"), nonBoolAddProps)
		                    .isValid()).isTrue();

		JsonNode dirPropSchema = objectMapper.readTree("""
				                                               {"type":"object","properties":{"working_dir":{"type":"string"}}}
				                                               """);
		assertThat(validator.validate(objectMapper.readTree("{\"working_dir\":\"../../etc\"}"), dirPropSchema)
		                    .errorMessage())
				.contains("Path traversal detected");

		// Null arguments with empty required array -> success
		JsonNode emptyRequired = objectMapper.readTree("""
				                                               {"type":"object","required":[],"properties":{}}
				                                               """);
		assertThat(validator.validate(null, emptyRequired).isValid()).isTrue();

		// Strict additionalProperties false with only declared properties -> success
		JsonNode strictSchema = objectMapper.readTree("""
				                                              {"type":"object","additionalProperties":false,"properties":{"k":{"type":"string"}}}
				                                              """);
		assertThat(validator.validate(objectMapper.readTree("{\"k\":\"v\"}"), strictSchema).isValid()).isTrue();

		// Non-integral number rejected for integer-typed property
		JsonNode intSchema = objectMapper.readTree("""
				                                           {"type":"object","properties":{"count":{"type":"integer"}}}
				                                           """);
		assertThat(validator.validate(objectMapper.readTree("{\"count\":1.5}"), intSchema).errorMessage())
				.contains("must be an integer");
	}

	@Test
	@DisplayName("Guardrail scanner wraps null tool output text safely")
	void guardrailNullTextWrapping() {
		McpGuardrailScanner scanner = new McpGuardrailScanner(new IngressSecretScanner(), objectMapper);
		assertThat(scanner.wrapToolOutputWithNonce("t", null)).startsWith("<tool_result").endsWith("</tool_result>");
	}

	@Test
	@DisplayName("Gemini adapter covers missing messages, developer role, null system text, and json_schema response")
	void geminiAdapterBranchCompletion() throws Exception {
		GeminiAdapter adapter = new GeminiAdapter(objectMapper);

		JsonNode noMessages = objectMapper.readTree(adapter.buildRequestBody("{\"model\":\"gemini-2.5-flash\"}", null));
		assertThat(noMessages.path("contents").isArray()).isTrue();

		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {"role": "developer", "content": "Be precise."},
				    {"role": "system", "content": null},
				    {"role": "assistant", "content": [{"type": "image_url", "image_url": {"url": "https://x/i.png"}}]},
				    {"role": "user", "content": null}
				  ],
				  "stop": "STOP_HERE",
				  "response_format": {"type": "json_schema", "json_schema": {"schema": {"type": "object"}}}
				}
				""";
		JsonNode res = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertThat(res.path("systemInstruction").path("parts").get(0).path("text").asString()).contains("Be precise.");
		assertThat(res.path("generationConfig").path("responseSchema").path("type").asString())
				.isEqualToIgnoringCase("object");
		assertThat(res.path("generationConfig").path("stopSequences").get(0).asString()).isEqualTo("STOP_HERE");

		// Empty tools array and positive max_tokens
		String body2 = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [{"role": "user", "content": "hi"}],
				  "tools": [],
				  "max_tokens": 512
				}
				""";
		JsonNode res2 = objectMapper.readTree(adapter.buildRequestBody(body2, null));
		assertThat(res2.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(512);
		assertThat(res2.has("tools")).isFalse();
	}

	@Test
	@DisplayName("Gemini SSE normalizer covers image-only parts and malformed function call finish reason")
	void geminiSseNormalizerBranchCompletion() {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "m", false);

		// Part with neither text, thought, nor functionCall
		assertThat(normalizer.normalizeLine(
				"data: {\"candidates\": [{\"content\": {\"parts\": [{\"type\":\"image\",\"data\":\"abc\"}]}}]}")).isEmpty();

		// MALFORMED_FUNCTION_CALL finish reason maps to tool_calls
		GeminiSseNormalizer malformed = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> out = malformed.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"MALFORMED_FUNCTION_CALL\"}]}");
		assertThat(out).hasSize(2);
		assertThat(out.getFirst()).contains("\"finish_reason\":\"tool_calls\"");
	}

	@Test
	@DisplayName("Stable no-op test ensuring fixture reuse does not trigger Mockito strict stubs")
	void fixtureIntegrity() {
		assertThat(apiKey.ownerId()).isEqualTo("tenant-cov");
		assertThat(properties.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("Gemini adapter joins multi-part assistant text and appends user text parts")
	void geminiAdapterMultiTextParts() throws Exception {
		GeminiAdapter adapter = new GeminiAdapter(objectMapper);

		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {"role": "assistant", "content": [{"type": "text", "text": "first"}, {"type": "text", "text": "second"}]},
				    {"role": "user", "content": [{"type": "text", "text": "user text"}]}
				  ]
				}
				""";
		JsonNode res = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertThat(res.path("contents").get(0).path("parts").get(0).path("text").asString())
				.isEqualTo("first\nsecond");
		assertThat(res.path("contents").get(1).path("parts").get(0).path("text").asString())
				.isEqualTo("user text");
	}

	@Test
	@DisplayName("Gemini SSE normalizer relays function calls with blank names")
	void geminiSseBlankFunctionName() {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> out = normalizer.normalizeLine(
				"data: {\"candidates\": [{\"content\": {\"parts\": [{\"functionCall\": {\"name\": \"\"}}]}}]}");
		assertThat(out).hasSize(1);
		assertThat(out.getFirst()).contains("\"tool_calls\"");
	}

	@Test
	@DisplayName("JSON-RPC request/resolution covers non-object meta, numeric progress tokens, and blank jsonrpc")
	void jsonRpcMetaEdgeCases() {
		// _meta present but not an object
		ObjectNode stringMeta = objectMapper.createObjectNode();
		stringMeta.put("_meta", "not-an-object");
		McpJsonRpcRequest reqMeta = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1), "ping", stringMeta
		);
		assertThat(reqMeta.resolveProtocolVersion()).isNull();
		assertThat(reqMeta.resolveProgressToken()).isNull();

// numeric progressToken (stringified by asText)
		ObjectNode numericToken = objectMapper.createObjectNode();
		numericToken.putObject("_meta").put("progressToken", 5);
		McpJsonRpcRequest reqNumeric = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(2), "ping", numericToken
		);
		assertThat(reqNumeric.resolveProgressToken()).isEqualTo("5");
		assertThat(reqNumeric.resolveProtocolVersion()).isNull();

		// blank jsonrpc falls back to "2.0"
		assertThat(new McpJsonRpcResponse("", objectMapper.getNodeFactory().numberNode(1), null, null).jsonrpc())
				.isEqualTo("2.0");
	}

	@Test
	@DisplayName("Schema validator and guardrail scanner handle explicit NullNode arguments")
	void nullNodeEdgeCases() throws Exception {
		McpJsonSchemaValidator validator = new McpJsonSchemaValidator();
		JsonNode requiredSchema = objectMapper.readTree("""
				                                                {"type":"object","required":["limit"],"properties":{"limit":{"type":"integer"}}}
				                                                """);
		assertThat(validator.validate(NullNode.getInstance(), requiredSchema).isValid())
				.isFalse();

		McpGuardrailScanner scanner = new McpGuardrailScanner(new IngressSecretScanner(), objectMapper);
		assertThat(scanner.scanArguments(NullNode.getInstance()).detected()).isFalse();
	}

	@Test
	@DisplayName("Header normalizer encodes control characters via base64 sentinel")
	void headerNormalizerControlChars() {
		String encoded = McpHeaderNormalizer.encodeHeaderValue("line\nbreak");
		assertThat(encoded).startsWith("=?base64?").endsWith("?=");
		assertThat(McpHeaderNormalizer.decodeHeaderValue(encoded))
				.isEqualTo("line\nbreak");
	}

	@Test
	@DisplayName("Aggregated catalog handles null defensive copy inputs")
	void aggregatedCatalogNullInputs() {
		McpAggregatedCatalog catalog = new McpAggregatedCatalog(null, null, null, Instant.now());
		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.resources()).isEmpty();
		assertThat(catalog.prompts()).isEmpty();
	}

	@Test
	@DisplayName("Router handles boundary and unlisted-server namespaces")
	void routerBoundaryRoutes() {
		properties.setServers(Map.of(
				"empty", new McpServerConfig(
						"empty", McpTransportType.STREAMABLE_HTTP,
						URI.create("http://localhost"), null, null, null,
						Set.of(), Set.of(), Set.of(), 10, true
				)
		));
		McpRouter router = new McpRouter(properties);

		// delimiter at position 0 -> falls through to un-namespaced matching
		assertThat(router.resolveToolRoute("__x").orElseThrow().rawTargetName()).isEqualTo("__x");

		// delimiter at the very end -> falls through to un-namespaced matching
		assertThat(router.resolveToolRoute("postgres__").orElseThrow().rawTargetName()).isEqualTo("postgres__");

		// un-namespaced tool resolved against a server with an empty allow list
		assertThat(router.resolveToolRoute("anything").orElseThrow().rawTargetName()).isEqualTo("anything");
	}

	@Test
	@DisplayName("Profile normalizers report usage across single and repeated token counter updates")
	void normalizersPartialUsage() {
		DeepSeekSseNormalizer deepSeek = new DeepSeekSseNormalizer(objectMapper, "deepseek-model", false);
		deepSeek.normalizeLine("data: {\"usage\":{\"prompt_tokens\":12}}");
		assertThat(deepSeek.usage()).isNotNull();
		assertThat(deepSeek.usage().promptTokens()).isEqualTo(12L);
		assertThat(deepSeek.usage().completionTokens()).isEqualTo(0L);

		DeepSeekSseNormalizer deepSeekCompletionOnly = new DeepSeekSseNormalizer(objectMapper, "deepseek-model", false);
		deepSeekCompletionOnly.normalizeLine("data: {\"usage\":{\"completion_tokens\":9}}");
		assertThat(deepSeekCompletionOnly.usage()).isNotNull();
		assertThat(deepSeekCompletionOnly.usage().completionTokens()).isEqualTo(9L);
		assertThat(deepSeekCompletionOnly.usage().promptTokens()).isEqualTo(0L);

		// Repeated updates exercise the non-null merge ternaries
		DeepSeekSseNormalizer deepSeekRepeated = new DeepSeekSseNormalizer(objectMapper, "deepseek-model", false);
		deepSeekRepeated.normalizeLine("data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}");
		deepSeekRepeated.normalizeLine("data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}");
		assertThat(deepSeekRepeated.usage()).isNotNull();
		assertThat(deepSeekRepeated.usage().promptTokens()).isEqualTo(3L);
		assertThat(deepSeekRepeated.usage().completionTokens()).isEqualTo(4L);

		GeminiSseNormalizer gemini = new GeminiSseNormalizer(objectMapper, "gemini-model", false);
		gemini.normalizeLine("data: {\"usageMetadata\":{\"candidatesTokenCount\":7}}");
		assertThat(gemini.usage()).isNotNull();
		assertThat(gemini.usage().completionTokens()).isEqualTo(7L);

		GeminiSseNormalizer geminiPromptOnly = new GeminiSseNormalizer(objectMapper, "gemini-model", false);
		geminiPromptOnly.normalizeLine("data: {\"usageMetadata\":{\"promptTokenCount\":3}}");
		assertThat(geminiPromptOnly.usage()).isNotNull();
		assertThat(geminiPromptOnly.usage().promptTokens()).isEqualTo(3L);
		assertThat(geminiPromptOnly.usage().completionTokens()).isEqualTo(0L);

		// Repeated updates exercise the non-null merge ternaries
		GeminiSseNormalizer geminiRepeated = new GeminiSseNormalizer(objectMapper, "gemini-model", false);
		geminiRepeated.normalizeLine("data: {\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":6}}");
		geminiRepeated.normalizeLine("data: {\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2}}");
		assertThat(geminiRepeated.usage()).isNotNull();
		assertThat(geminiRepeated.usage().promptTokens()).isEqualTo(1L);
		assertThat(geminiRepeated.usage().completionTokens()).isEqualTo(2L);
	}

	@Test
	@DisplayName("Anthropic adapter covers developer role, unsupported roles, and broken tool arguments")
	void anthropicAdapterBranchCompletion() throws Exception {
		AnthropicAdapter adapter = new AnthropicAdapter(objectMapper);

		String body = """
				{
				  "model": "claude-sonnet-4-5",
				  "messages": [
				    {"role": "developer", "content": "Be precise."},
				    {"role": "function", "content": "unsupported"},
				    {"role": "assistant", "tool_calls": [
				      {"id": "call_1", "function": {"name": "fn", "arguments": "not-json"}}
				    ]},
				    {"role": "tool", "tool_call_id": null, "content": "result"},
				    {"role": "user", "content": "ok"}
				  ]
				}
				""";
		JsonNode res = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertThat(res.path("system").asString()).isEqualTo("Be precise.");

		JsonNode assistant = null;
		JsonNode toolResult = null;
		for (JsonNode message : res.path("messages")) {
			if ("assistant".equals(message.path("role").asString())) {
				assistant = message;
			} else if ("user".equals(message.path("role").asString())
					&& message.path("content").get(0) != null
					&& "tool_result".equals(message.path("content").get(0).path("type").asString())) {
				toolResult = message.path("content").get(0);
			}
		}
		assertThat(assistant).isNotNull();
		assertThat(assistant.path("content").get(0).path("type").asString()).isEqualTo("tool_use");
		assertThat(assistant.path("content").get(0).path("input").isObject()).isTrue();

		assertThat(toolResult).isNotNull();
		assertThat(toolResult.path("type").asString()).isEqualTo("tool_result");
		assertThat(toolResult.path("tool_use_id").asString()).isEmpty();
	}

	@Test
	@DisplayName("Usage accessors expose every null-projection arm (white-box fixtures)")
	@SuppressWarnings("DataFlowIssue")
	void normalizerUsageNullArms() {
		// White-box: with usage metadata present, asLong(...) defaults missing counters to 0L (never null),
		// so the null-arms of usage() are provably unreachable through black-box streams. Forcing them via
		// ReflectionTestUtils (spring-test; legal on JDK 25 unnamed-module classpath) completes JaCoCo's
		// branch/complexity graph for the accessor.
		GeminiSseNormalizer geminiOutOnly = new GeminiSseNormalizer(objectMapper, "gemini-model", true);
		ReflectionTestUtils.setField(geminiOutOnly, "inputTokens", null);
		ReflectionTestUtils.setField(geminiOutOnly, "outputTokens", 5L);
		assertThat(geminiOutOnly.usage()).isNotNull();
		assertThat(geminiOutOnly.usage().promptTokens()).isEqualTo(0L);
		assertThat(geminiOutOnly.usage().completionTokens()).isEqualTo(5L);

		GeminiSseNormalizer geminiInOnly = new GeminiSseNormalizer(objectMapper, "gemini-model", true);
		ReflectionTestUtils.setField(geminiInOnly, "inputTokens", 5L);
		ReflectionTestUtils.setField(geminiInOnly, "outputTokens", null);
		assertThat(geminiInOnly.usage()).isNotNull();
		assertThat(geminiInOnly.usage().promptTokens()).isEqualTo(5L);
		assertThat(geminiInOnly.usage().completionTokens()).isEqualTo(0L);

		DeepSeekSseNormalizer deepSeekOutOnly = new DeepSeekSseNormalizer(objectMapper, "deepseek-model", true);
		ReflectionTestUtils.setField(deepSeekOutOnly, "promptTokens", null);
		ReflectionTestUtils.setField(deepSeekOutOnly, "completionTokens", 5L);
		assertThat(deepSeekOutOnly.usage()).isNotNull();
		assertThat(deepSeekOutOnly.usage().promptTokens()).isEqualTo(0L);
		assertThat(deepSeekOutOnly.usage().completionTokens()).isEqualTo(5L);

		DeepSeekSseNormalizer deepSeekInOnly = new DeepSeekSseNormalizer(objectMapper, "deepseek-model", true);
		ReflectionTestUtils.setField(deepSeekInOnly, "promptTokens", 5L);
		ReflectionTestUtils.setField(deepSeekInOnly, "completionTokens", null);
		assertThat(deepSeekInOnly.usage()).isNotNull();
		assertThat(deepSeekInOnly.usage().promptTokens()).isEqualTo(5L);
		assertThat(deepSeekInOnly.usage().completionTokens()).isEqualTo(0L);
	}

	@Test
	@DisplayName("McpServerCircuitBreakerManager facade behaves correctly")
	void circuitBreakerFacade() {
		McpServerCircuitBreakerManager manager = new McpServerCircuitBreakerManager(
				properties,
				new McpCatalogCache(properties)
		);
		assertThat(manager.tryAcquire("srv")).isTrue();
		manager.recordFailure("srv");
		manager.recordSuccess("srv");
		manager.reset("srv");
		assertThat(manager.getBreaker("srv")).isNotNull();
	}

	private McpServerConfig serverConfig(String name) {
		return new McpServerConfig(
				name, McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"), new SensitiveString("key"), null, null,
				Set.of(), Set.of(), Set.of(), 10, true
		);
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> mockUpstream(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		lenient().when(response.statusCode()).thenReturn(status);
		lenient().when(response.body()).thenReturn(body);
		return response;
	}
}