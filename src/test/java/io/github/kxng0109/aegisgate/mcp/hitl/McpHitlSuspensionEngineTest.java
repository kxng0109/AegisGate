package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.aegisgate.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Human-in-the-Loop (HITL) Suspension Engine Unit Tests")
class McpHitlSuspensionEngineTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpGatewayProperties properties;
	private McpAeadResumptionTokenService tokenService;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private McpHitlSuspensionEngine suspensionEngine;
	private McpServerConfig hitlServer;
	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("super-secret-hitl-key-32-bytes!!"));
		tokenService = new McpAeadResumptionTokenService(properties, objectMapper);

		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		suspensionEngine = new McpHitlSuspensionEngine(properties, tokenService, redisTemplate, objectMapper);

		hitlServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of("execute_sql", "*:delete_*"),
				100,
				true
		);

		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-1"),
				"gw-",
				"tenant-corp",
				"prod-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
	}

	@Test
	@DisplayName("isHitlRequired identifies tools requiring approval")
	void isHitlRequiredScenarios() {
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "execute_sql", "postgres__execute_sql")).isTrue();
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "delete_row", "postgres:delete_row")).isTrue();
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "read_data", "postgres__read_data")).isFalse();
	}

	@Test
	@DisplayName("Suspends privileged tool execution when no resumption token is present")
	void suspendsExecutionWithoutToken() {
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1),
				"tools/call",
				params
		);

		Optional<McpJsonRpcResponse> suspendedOpt = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(suspendedOpt).isPresent();
		McpJsonRpcResponse response = suspendedOpt.get();
		assertThat(response.isSuccess()).isTrue();

		ObjectNode resultNode = (ObjectNode) response.result();
		assertThat(resultNode.get("resultType").asText()).isEqualTo("input_required");
		assertThat(resultNode.get("requestState").asText()).startsWith("v2.aead.");
		assertThat(resultNode.get("inputRequests").has("human_approval")).isTrue();

		verify(valueOperations).set(startsWith("mcp:hitl:pending:"), anyString(), anyLong(), any());
	}

	@Test
	@DisplayName("Permits execution and enforces single-use replay deletion when token is approved")
	void permitsExecutionWithApprovedToken() {
		String argsJson = "{\"sql\":\"DROP TABLE users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-approved-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		String token = tokenService.mintToken(claims);

		// Client sends request with resumption token in params
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(2),
				"tools/call",
				params
		);

		when(valueOperations.get("mcp:hitl:approved:tok-approved-1")).thenReturn("APPROVED");

		Optional<McpJsonRpcResponse> cleared = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(cleared).isEmpty(); // Cleared to execute!
		verify(redisTemplate).delete("mcp:hitl:approved:tok-approved-1");
		verify(redisTemplate).delete("mcp:hitl:pending:tok-approved-1");
	}

	@Test
	@DisplayName("Extracts resumption token from _meta.requestState and re-suspends if not approved")
	void extractsTokenFromMetaAndResuspendsIfNotApproved() {
		String argsJson = "{\"sql\":\"DROP TABLE users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-pending-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		String token = tokenService.mintToken(claims);

		// Token in _meta instead of top-level params
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.putObject("_meta").put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(3),
				"tools/call",
				params
		);

		when(valueOperations.get("mcp:hitl:approved:tok-pending-1")).thenReturn(null); // Not approved

		Optional<McpJsonRpcResponse> suspendedOpt = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(suspendedOpt).isPresent();
		assertThat(suspendedOpt.get().isSuccess()).isTrue();
	}
}
