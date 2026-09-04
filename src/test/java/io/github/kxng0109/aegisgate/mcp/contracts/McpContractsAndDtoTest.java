package io.github.kxng0109.aegisgate.mcp.contracts;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.hitl.McpResumptionClaims;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import io.github.kxng0109.aegisgate.mcp.router.McpResolvedRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Contracts, Domain Models & DTO Unit Tests")
class McpContractsAndDtoTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("McpProtocolVersion evaluates supported and unsupported versions correctly")
	void mcpProtocolVersionNegotiation() {
		assertThat(McpProtocolVersion.isSupported("2026-07-28")).isTrue();
		assertThat(McpProtocolVersion.isSupported("2025-11-25")).isTrue();
		assertThat(McpProtocolVersion.isSupported("2024-11-05")).isTrue();
		assertThat(McpProtocolVersion.isSupported("  2026-07-28  ")).isTrue();

		assertThat(McpProtocolVersion.isSupported("2023-01-01")).isFalse();
		assertThat(McpProtocolVersion.isSupported("")).isFalse();
		assertThat(McpProtocolVersion.isSupported(null)).isFalse();

		assertThat(McpProtocolVersion.SUPPORTED_VERSIONS).hasSize(3);
		assertThat(McpProtocolVersion.SUPPORTED_VERSIONS_ORDERED).containsExactly(
				"2026-07-28",
				"2025-11-25",
				"2024-11-05"
		);
		assertThat(McpProtocolVersion.LATEST).isEqualTo("2026-07-28");
	}

	@Test
	@DisplayName("McpTransportType covers all supported transport types")
	void mcpTransportTypeEnum() {
		assertThat(McpTransportType.valueOf("STREAMABLE_HTTP")).isEqualTo(McpTransportType.STREAMABLE_HTTP);
		assertThat(McpTransportType.valueOf("HTTP_SSE")).isEqualTo(McpTransportType.HTTP_SSE);
		assertThat(McpTransportType.valueOf("STDIO")).isEqualTo(McpTransportType.STDIO);
		assertThat(McpTransportType.values()).hasSize(3);
	}

	@Test
	@DisplayName("McpJsonRpcRequest evaluates notifications, protocol versions, and progress tokens")
	void mcpJsonRpcRequestOperations() {
		// Standard request
		ObjectNode params = objectMapper.createObjectNode();
		ObjectNode meta = params.putObject("_meta");
		meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
		meta.put("progressToken", "prog-101");

		McpJsonRpcRequest req = new McpJsonRpcRequest(
				null,
				objectMapper.getNodeFactory().numberNode(1),
				"tools/call",
				params
		);
		assertThat(req.jsonrpc()).isEqualTo("2.0");
		assertThat(req.id()).isNotNull();
		assertThat(req.id().asInt()).isEqualTo(1);
		assertThat(req.isNotification()).isFalse();
		assertThat(req.resolveProtocolVersion()).isEqualTo("2026-07-28");
		assertThat(req.resolveProgressToken()).isEqualTo("prog-101");

		// Notification (null id or NullNode)
		McpJsonRpcRequest notif = new McpJsonRpcRequest("2.0", null, "notifications/initialized", null);
		assertThat(notif.isNotification()).isTrue();
		assertThat(notif.resolveProtocolVersion()).isNull();
		assertThat(notif.resolveProgressToken()).isNull();

		McpJsonRpcRequest notifNullNode = new McpJsonRpcRequest(
				"",
				tools.jackson.databind.node.NullNode.getInstance(),
				"ping",
				objectMapper.createObjectNode().putObject("_meta")
		);
		assertThat(notifNullNode.jsonrpc()).isEqualTo("2.0");
		assertThat(notifNullNode.isNotification()).isTrue();
		assertThat(notifNullNode.resolveProtocolVersion()).isNull();
		assertThat(notifNullNode.resolveProgressToken()).isNull();

		// Blank jsonrpc
		McpJsonRpcRequest blankRpc = new McpJsonRpcRequest(
				"   ",
				objectMapper.getNodeFactory().textNode("id-1"),
				"ping",
				null
		);
		assertThat(blankRpc.jsonrpc()).isEqualTo("2.0");

		// Legacy protocolVersion in _meta
		ObjectNode legacyParams = objectMapper.createObjectNode();
		legacyParams.putObject("_meta").put("protocolVersion", "2025-11-25");
		McpJsonRpcRequest legacyReq = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().textNode("req-2"),
				"ping",
				legacyParams
		);
		assertThat(legacyReq.resolveProtocolVersion()).isEqualTo("2025-11-25");
	}

	@Test
	@DisplayName("McpJsonRpcResponse builds valid JSON nodes for success and error payloads")
	void mcpJsonRpcResponseSerialization() {
		JsonNode id = objectMapper.getNodeFactory().numberNode(42);

		// Success response
		ObjectNode resObj = objectMapper.createObjectNode().put("status", "ok");
		McpJsonRpcResponse success = McpJsonRpcResponse.success(id, resObj);
		assertThat(success.isSuccess()).isTrue();
		ObjectNode successNode = success.toJsonNode(objectMapper);
		assertThat(successNode.get("jsonrpc").asText()).isEqualTo("2.0");
		assertThat(successNode.get("id").asInt()).isEqualTo(42);
		assertThat(successNode.get("result").get("status").asText()).isEqualTo("ok");

		// Failure response
		McpJsonRpcError error = McpJsonRpcError.invalidParams("bad param");
		McpJsonRpcResponse failure = McpJsonRpcResponse.failure(id, error);
		assertThat(failure.isSuccess()).isFalse();
		ObjectNode failureNode = failure.toJsonNode(objectMapper);
		assertThat(failureNode.get("error").get("code").asInt()).isEqualTo(McpJsonRpcError.INVALID_PARAMS);
		assertThat(failureNode.get("error").get("message").asText()).contains("bad param");

		// Factory failure with data
		ObjectNode errData = objectMapper.createObjectNode().put("field", "query");
		McpJsonRpcResponse failureWithData = McpJsonRpcResponse.failure(null, -32000, "Custom error", errData);
		ObjectNode failureDataNode = failureWithData.toJsonNode(objectMapper);
		assertThat(failureDataNode.get("id").isNull()).isTrue();
		assertThat(failureDataNode.get("error").get("data").get("field").asText()).isEqualTo("query");

		// Empty result fallback
		McpJsonRpcResponse emptyRes = new McpJsonRpcResponse("2.0", id, null, null);
		ObjectNode emptyNode = emptyRes.toJsonNode(objectMapper);
		assertThat(emptyNode.get("result").isObject()).isTrue();
	}

	@Test
	@DisplayName("McpJsonRpcError provides comprehensive error factory methods and error codes")
	void mcpJsonRpcErrorFactories() {
		assertThat(McpJsonRpcError.parseError("syntax").code()).isEqualTo(-32700);
		assertThat(McpJsonRpcError.invalidRequest("missing id").code()).isEqualTo(-32600);
		assertThat(McpJsonRpcError.methodNotFound("tools/unknown").code()).isEqualTo(-32601);
		assertThat(McpJsonRpcError.invalidParams("missing field").code()).isEqualTo(-32602);
		assertThat(McpJsonRpcError.internalError("out of memory").code()).isEqualTo(-32603);
		assertThat(McpJsonRpcError.headerMismatch("protocol mismatch").code()).isEqualTo(-32020);
		assertThat(McpJsonRpcError.accessDenied("rbac violation").code()).isEqualTo(-32025);
		assertThat(McpJsonRpcError.circuitBreakerTripped("postgres").code()).isEqualTo(-32024);

		McpJsonRpcError unsupported = McpJsonRpcError.unsupportedVersion("1.0", objectMapper);
		assertThat(unsupported.code()).isEqualTo(-32022);
		assertThat(unsupported.data().get("requested").asText()).isEqualTo("1.0");
		assertThat(unsupported.data().get("supported").isArray()).isTrue();
	}

	@Test
	@DisplayName("McpToolDefinition serializes to JSON node with default inputSchema if null")
	void mcpToolDefinitionSerialization() {
		McpToolDefinition toolWithSchema = new McpToolDefinition(
				"postgres__query",
				"Executes a SQL query",
				objectMapper.createObjectNode().put("type", "object"),
				objectMapper.createObjectNode().put("version", "1.0")
		);
		ObjectNode json1 = toolWithSchema.toJsonNode(objectMapper);
		assertThat(json1.get("name").asText()).isEqualTo("postgres__query");
		assertThat(json1.get("description").asText()).isEqualTo("Executes a SQL query");
		assertThat(json1.get("inputSchema").get("type").asText()).isEqualTo("object");
		assertThat(json1.get("_meta").get("version").asText()).isEqualTo("1.0");

		McpToolDefinition toolWithoutSchema = new McpToolDefinition("simple_tool", null, null, null);
		ObjectNode json2 = toolWithoutSchema.toJsonNode(objectMapper);
		assertThat(json2.get("name").asText()).isEqualTo("simple_tool");
		assertThat(json2.has("description")).isFalse();
		assertThat(json2.get("inputSchema").get("type").asText()).isEqualTo("object");
		assertThat(json2.has("_meta")).isFalse();
	}

	@Test
	@DisplayName("McpResourceDefinition and McpPromptDefinition serialize cleanly to JSON nodes")
	void mcpResourceAndPromptSerialization() {
		McpResourceDefinition res = new McpResourceDefinition(
				"postgres://table/users",
				"Users Table",
				"User records",
				"application/json",
				null
		);
		ObjectNode resNode = res.toJsonNode(objectMapper);
		assertThat(resNode.get("uri").asText()).isEqualTo("postgres://table/users");
		assertThat(resNode.get("name").asText()).isEqualTo("Users Table");
		assertThat(resNode.get("description").asText()).isEqualTo("User records");
		assertThat(resNode.get("mimeType").asText()).isEqualTo("application/json");

		McpPromptArgument arg = new McpPromptArgument("topic", "Topic description", true);
		ObjectNode argNode = arg.toJsonNode(objectMapper);
		assertThat(argNode.get("name").asText()).isEqualTo("topic");
		assertThat(argNode.get("description").asText()).isEqualTo("Topic description");
		assertThat(argNode.get("required").asBoolean()).isTrue();

		McpPromptDefinition prompt = new McpPromptDefinition("summarize", "Summarization prompt", List.of(arg), null);
		ObjectNode prmNode = prompt.toJsonNode(objectMapper);
		assertThat(prmNode.get("name").asText()).isEqualTo("summarize");
		assertThat(prmNode.get("arguments").isArray()).isTrue();
		assertThat(prmNode.get("arguments").get(0).get("name").asText()).isEqualTo("topic");

		// Null arguments and null metadata
		McpPromptDefinition emptyPrompt = new McpPromptDefinition("simple_prompt", null, null, null);
		ObjectNode emptyPrmNode = emptyPrompt.toJsonNode(objectMapper);
		assertThat(emptyPrmNode.get("name").asText()).isEqualTo("simple_prompt");
		assertThat(emptyPrmNode.has("arguments")).isFalse();

		McpResourceDefinition emptyRes = new McpResourceDefinition("res://simple", "Simple", null, null, null);
		ObjectNode emptyResNode = emptyRes.toJsonNode(objectMapper);
		assertThat(emptyResNode.get("uri").asText()).isEqualTo("res://simple");
	}

	@Test
	@DisplayName("McpServerConfig initializes defaults and stores defensive copies")
	void mcpServerConfigDefaults() {
		McpServerConfig server = new McpServerConfig(
				"postgres",
				null,
				URI.create("http://localhost:8081"),
				new SensitiveString("secret"),
				null,
				null,
				Set.of("run_query"),
				Set.of("drop_db"),
				Set.of("delete_all"),
				500,
				true
		);

		assertThat(server.name()).isEqualTo("postgres");
		assertThat(server.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
		assertThat(server.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(server.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
		assertThat(server.allowedTools()).containsExactly("run_query");
		assertThat(server.deniedTools()).containsExactly("drop_db");
		assertThat(server.hitlRequiredTools()).containsExactly("delete_all");
		assertThat(server.maxConcurrentRequests()).isEqualTo(500);
		assertThat(server.enabled()).isTrue();
	}

	@Test
	@DisplayName("McpAggregatedCatalog and McpResolvedRoute verify defensive copying and factory methods")
	void mcpAggregatedCatalogAndResolvedRoute() {
		McpAggregatedCatalog empty = McpAggregatedCatalog.empty();
		assertThat(empty.tools()).isEmpty();
		assertThat(empty.resources()).isEmpty();
		assertThat(empty.prompts()).isEmpty();
		assertThat(empty.fetchedAt()).isNotNull();

		McpServerConfig cfg = new McpServerConfig(
				"pg",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8080"),
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				true
		);
		McpResolvedRoute route = new McpResolvedRoute(cfg, "query", "pg__query");
		assertThat(route.serverConfig()).isEqualTo(cfg);
		assertThat(route.rawTargetName()).isEqualTo("query");
		assertThat(route.namespacedName()).isEqualTo("pg__query");
	}

	@Test
	@DisplayName("McpResumptionClaims evaluates expiration correctly")
	void mcpResumptionClaimsExpiration() {
		Instant now = Instant.now();
		McpResumptionClaims expiredClaims = new McpResumptionClaims(
				"t1",
				"owner-1",
				"tool-a",
				"hash1",
				now.minusSeconds(600),
				now.minusSeconds(300)
		);
		assertThat(expiredClaims.isExpired()).isTrue();

		McpResumptionClaims validClaims = new McpResumptionClaims(
				"t2",
				"owner-1",
				"tool-a",
				"hash1",
				now,
				now.plusSeconds(300)
		);
		assertThat(validClaims.isExpired()).isFalse();
	}
}
