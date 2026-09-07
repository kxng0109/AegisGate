package io.github.kxng0109.aegisgate.mcp.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import io.github.kxng0109.aegisgate.mcp.hitl.McpHitlSuspensionEngine;
import io.github.kxng0109.aegisgate.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import io.github.kxng0109.aegisgate.mcp.router.McpCatalogAggregator;
import io.github.kxng0109.aegisgate.mcp.router.McpRouter;
import io.github.kxng0109.aegisgate.mcp.security.McpGuardrailScanner;
import io.github.kxng0109.aegisgate.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP version-negotiation matrix and batch rejection")
class McpVersionNegotiationMatrixTest {

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

	private McpStreamableHttpController controller;
	private VirtualApiKey validApiKey;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		properties.setServers(Map.of(
				"postgres", new McpServerConfig(
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
				)
		));

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

		lenient().when(catalogAggregator.getAggregatedCatalog())
		         .thenReturn(McpAggregatedCatalog.empty());
		lenient().when(rbacPolicyEngine.filterCatalog(any(), any()))
		         .thenAnswer(invocation -> invocation.getArgument(0));
	}

	private MockHttpServletRequest keyedRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("virtualApiKey", validApiKey);
		return request;
	}

	private String raw(String method, String paramsJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
				+ (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
	}

	private String metaParams(String version, String capsJson) {
		return "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ version + "\",\"io.modelcontextprotocol/clientCapabilities\":" + capsJson + "}}";
	}

	static Stream<Arguments> versionMatrix() {
		return Stream.of(
				Arguments.of(
						"2026-07-28", "2026-07-28", "{\"tools\":{}}", "tools/list",
						HttpStatus.OK, 0
				),
				Arguments.of(
						"2026-07-28", "2025-11-25", "{\"tools\":{}}", "tools/list",
						HttpStatus.BAD_REQUEST, -32020
				),
				Arguments.of(
						"1900-01-01", "1900-01-01", "{}", "ping",
						HttpStatus.BAD_REQUEST, -32022
				),
				Arguments.of(
						"2026-07-28", "2026-07-28", "{}", "tools/call",
						HttpStatus.BAD_REQUEST, -32021
				)
		);
	}

	@ParameterizedTest(name = "header={0} body={1} method={3} -> {4}/{5}")
	@MethodSource("versionMatrix")
	@DisplayName("version-negotiation matrix")
	void versionMatrix(String header, String body, String caps, String method,
	                   HttpStatus status, int code) {
		ResponseEntity<String> resp = controller.handleStreamableHttp(
				raw(method, metaParams(body, caps)), header, null, keyedRequest());
		assertThat(resp.getStatusCode()).isEqualTo(status);
		if (code != 0) {
			assertThat(resp.getBody()).contains(String.valueOf(code));
		}
	}

	@Test
	@DisplayName("modern paramless request is rejected with -32602")
	void modernParamlessRejected() throws Exception {
		ResponseEntity<String> resp = controller.handleStreamableHttp(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null, null, keyedRequest());
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode tree = objectMapper.readTree(resp.getBody());
		assertThat(tree.path("error").path("code").asInt()).isEqualTo(-32602);
	}

	@Test
	@DisplayName("-32022 carries data.supported and data.requested")
	void unsupportedVersionDataShape() throws Exception {
		ResponseEntity<String> resp = controller.handleStreamableHttp(
				raw("ping", metaParams("1900-01-01", "{}")), "1900-01-01", null, keyedRequest());
		JsonNode tree = objectMapper.readTree(resp.getBody());
		assertThat(tree.path("error").path("code").asInt()).isEqualTo(-32022);
		assertThat(tree.path("error").path("data").has("supported")).isTrue();
		assertThat(tree.path("error").path("data").has("requested")).isTrue();
	}

	@Test
	@DisplayName("-32021 carries data.requiredCapabilities as an object")
	void missingCapabilityDataShape() throws Exception {
		ResponseEntity<String> resp = controller.handleStreamableHttp(
				raw(
						"tools/call",
						"{\"name\":\"x\",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}"
				),
				"2026-07-28", null, keyedRequest()
		);
		JsonNode tree = objectMapper.readTree(resp.getBody());
		assertThat(tree.path("error").path("code").asInt()).isEqualTo(-32021);
		JsonNode caps = tree.path("error").path("data").path("requiredCapabilities");
		assertThat(caps.isObject()).isTrue();
		assertThat(caps.has("tools")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"[]",
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]",
			"[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]"
	})
	@DisplayName("batch arrays are rejected as a single -32600 object")
	void batchRejectedAsSingleObject(String arrayBody) throws Exception {
		ResponseEntity<String> resp = controller.handleStreamableHttp(
				arrayBody, null, null, keyedRequest());
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode tree = objectMapper.readTree(resp.getBody());
		assertThat(tree.isObject()).isTrue();
		assertThat(tree.isArray()).isFalse();
		assertThat(tree.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(tree.has("id")).isFalse();
	}
}
