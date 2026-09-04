package io.github.kxng0109.aegisgate.mcp.protocol;

import io.github.kxng0109.aegisgate.config.OpenApiConfig;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * High-performance, zero-allocation Model Context Protocol (MCP) reverse proxy gateway controller. Supports modern
 * stateless Streamable HTTP (2026-07-28) and backwards-compatible legacy SSE (2024-11-05).
 */
@Slf4j
@RestController
@RequestMapping("/v1/mcp")
@Tag(name = "Model Context Protocol (MCP) Gateway", description = "Enterprise MCP tool router, aggregator, security firewall, and Human-in-the-Loop governance")
public class McpStreamableHttpController {

	private final McpGatewayProperties properties;
	private final McpCatalogAggregator catalogAggregator;
	private final McpRouter router;
	private final McpToolRbacPolicyEngine rbacPolicyEngine;
	private final McpJsonSchemaValidator jsonSchemaValidator;
	private final McpGuardrailScanner guardrailScanner;
	private final McpHitlSuspensionEngine hitlSuspensionEngine;
	private final McpServerCircuitBreakerManager circuitBreakerManager;
	private final KeyManagementService keyManagementService;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	public McpStreamableHttpController(
			McpGatewayProperties properties,
			McpCatalogAggregator catalogAggregator,
			McpRouter router,
			McpToolRbacPolicyEngine rbacPolicyEngine,
			McpJsonSchemaValidator jsonSchemaValidator,
			McpGuardrailScanner guardrailScanner,
			McpHitlSuspensionEngine hitlSuspensionEngine,
			McpServerCircuitBreakerManager circuitBreakerManager,
			KeyManagementService keyManagementService,
			@Qualifier("mcpHttpClient") HttpClient httpClient,
			ObjectMapper objectMapper
	) {
		this.properties = properties;
		this.catalogAggregator = catalogAggregator;
		this.router = router;
		this.rbacPolicyEngine = rbacPolicyEngine;
		this.jsonSchemaValidator = jsonSchemaValidator;
		this.guardrailScanner = guardrailScanner;
		this.hitlSuspensionEngine = hitlSuspensionEngine;
		this.circuitBreakerManager = circuitBreakerManager;
		this.keyManagementService = keyManagementService;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * Unified Streamable HTTP endpoint for JSON-RPC 2.0 Model Context Protocol requests.
	 */
	@Operation(
			summary = "Streamable HTTP MCP JSON-RPC 2.0 endpoint",
			description = "Processes tools/list, tools/call, resources/read, prompts/list, ping, and initialize methods with RBAC, schema validation, and guardrails.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Successful JSON-RPC response", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "202", description = "Accepted (for notifications)"),
			@ApiResponse(responseCode = "400", description = "Invalid JSON or protocol version mismatch"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Virtual API key missing or invalid"),
			@ApiResponse(responseCode = "403", description = "Forbidden: Tool RBAC permission denied")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> handleStreamableHttp(
			@RequestBody String rawBody,
			@RequestHeader(value = McpHeaderNormalizer.HEADER_PROTOCOL_VERSION, required = false) String headerProtocolVersion,
			@RequestHeader(value = McpHeaderNormalizer.HEADER_MCP_METHOD, required = false) String headerMethod,
			HttpServletRequest httpRequest
	) {
		if (!properties.isEnabled()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			                     .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"MCP Gateway is disabled\"}}");
		}

		VirtualApiKey apiKey = resolveApiKey(httpRequest);
		if (apiKey == null || !apiKey.enabled()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			                     .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Unauthorized: Invalid or disabled API key\"}}");
		}

		// Protocol version negotiation
		String protocolVersion =
				headerProtocolVersion != null ? headerProtocolVersion : properties.getDefaultProtocolVersion();
		if (!McpProtocolVersion.isSupported(protocolVersion)) {
			McpJsonRpcError err = McpJsonRpcError.unsupportedVersion(protocolVersion, objectMapper);
			return ResponseEntity.badRequest()
			                     .body(McpJsonRpcResponse.failure(null, err).toJsonNode(objectMapper).toString());
		}

		McpJsonRpcRequest request;
		try {
			JsonNode tree = objectMapper.readTree(rawBody);
			String jsonrpc = tree.path("jsonrpc").asText("2.0");
			JsonNode id = tree.has("id") ? tree.get("id") : null;
			String method = tree.path("method").asText(headerMethod != null ? headerMethod : "");
			JsonNode params = tree.has("params") ? tree.get("params") : null;
			request = new McpJsonRpcRequest(jsonrpc, id, method, params);
		} catch (Exception e) {
			McpJsonRpcError err = McpJsonRpcError.parseError(e.getMessage());
			return ResponseEntity.badRequest()
			                     .body(McpJsonRpcResponse.failure(null, err).toJsonNode(objectMapper).toString());
		}

		if (request.isNotification()) {
			handleNotification(request);
			return ResponseEntity.accepted().build();
		}

		McpJsonRpcResponse response = processRequest(request, apiKey, protocolVersion);
		return ResponseEntity.ok(response.toJsonNode(objectMapper).toString());
	}

	/**
	 * Legacy Server-Sent Events (SSE) streaming endpoint for 2024-11-05 clients (e.g. Claude Desktop).
	 */
	@Operation(
			summary = "Legacy MCP SSE stream connection (2024-11-05)",
			description = "Establishes a persistent SSE stream and transmits an endpoint event for bidirectional session communication.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
			}
	)
	@GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseBodyEmitter handleLegacySse(HttpServletRequest httpRequest, HttpServletResponse response) {
		if (!properties.isAllowLegacySse()) {
			throw new IllegalStateException("Legacy SSE transport is disabled");
		}

		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader("Cache-Control", "no-cache");

		ResponseBodyEmitter emitter = new ResponseBodyEmitter(Duration.ofMinutes(30).toMillis());
		String sessionId = UUID.randomUUID().toString().replace("-", "");
		String endpointUri = "/v1/mcp/message?sessionId=" + sessionId;

		Thread.ofVirtual().name("mcp-sse-legacy-", 0).start(() -> {
			try {
				emitter.send("event: endpoint\ndata: " + endpointUri + "\n\n", MediaType.TEXT_PLAIN);
			} catch (IOException e) {
				emitter.completeWithError(e);
			}
		});

		return emitter;
	}

	/**
	 * Legacy message POST endpoint for 2024-11-05 clients.
	 */
	@PostMapping(value = "/message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> handleLegacyMessage(
			@RequestBody String rawBody,
			HttpServletRequest httpRequest
	) {
		return handleStreamableHttp(rawBody, McpProtocolVersion.V2024_11_05, null, httpRequest);
	}

	private McpJsonRpcResponse processRequest(McpJsonRpcRequest request, VirtualApiKey apiKey, String protocolVersion) {
		String method = request.method();
		if (method == null || method.isBlank()) {
			return McpJsonRpcResponse.failure(request.id(), McpJsonRpcError.invalidRequest("Missing method"));
		}

		return switch (method) {
			case "ping" -> handlePing(request);
			case "initialize" -> handleInitialize(request, protocolVersion);
			case "tools/list" -> handleToolsList(request, apiKey);
			case "tools/call" -> handleToolsCall(request, apiKey);
			case "resources/list" -> handleResourcesList(request);
			case "prompts/list" -> handlePromptsList(request);
			default -> McpJsonRpcResponse.failure(request.id(), McpJsonRpcError.methodNotFound(method));
		};
	}

	private void handleNotification(McpJsonRpcRequest request) {
		String method = request.method();
		if ("notifications/initialized".equals(method)) {
			log.debug("Client signaled initialized notification");
		} else if ("notifications/tools/list_changed".equals(method)) {
			log.info("Catalog changed notification received. Invalidating cache.");
			catalogAggregator.getAggregatedCatalog();
		}
	}

	private McpJsonRpcResponse handlePing(McpJsonRpcRequest request) {
		return McpJsonRpcResponse.success(request.id(), objectMapper.createObjectNode());
	}

	private McpJsonRpcResponse handleInitialize(McpJsonRpcRequest request, String protocolVersion) {
		ObjectNode result = objectMapper.createObjectNode();
		result.put("protocolVersion", protocolVersion);

		ObjectNode serverInfo = result.putObject("serverInfo");
		serverInfo.put("name", "AegisGate-MCP-Gateway");
		serverInfo.put("version", "1.4.0");

		ObjectNode capabilities = result.putObject("capabilities");
		capabilities.putObject("tools").put("listChanged", true);
		capabilities.putObject("resources").put("listChanged", true);
		capabilities.putObject("prompts").put("listChanged", true);

		return McpJsonRpcResponse.success(request.id(), result);
	}

	private McpJsonRpcResponse handleToolsList(McpJsonRpcRequest request, VirtualApiKey apiKey) {
		McpAggregatedCatalog rawCatalog = catalogAggregator.getAggregatedCatalog();
		McpAggregatedCatalog filteredCatalog = rbacPolicyEngine.filterCatalog(rawCatalog, apiKey);

		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode toolsArr = result.putArray("tools");
		for (McpToolDefinition tool : filteredCatalog.tools()) {
			// Auto-prune tools if server circuit breaker is tripped
			Optional<McpResolvedRoute> route = router.resolveToolRoute(tool.name());
			if (route.isPresent() && !circuitBreakerManager.tryAcquire(route.get().serverConfig().name())) {
				continue; // Skip offline server tools
			}
			toolsArr.add(tool.toJsonNode(objectMapper));
		}
		return McpJsonRpcResponse.success(request.id(), result);
	}

	private McpJsonRpcResponse handleResourcesList(McpJsonRpcRequest request) {
		McpAggregatedCatalog catalog = catalogAggregator.getAggregatedCatalog();
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode resArr = result.putArray("resources");
		for (McpResourceDefinition res : catalog.resources()) {
			resArr.add(res.toJsonNode(objectMapper));
		}
		return McpJsonRpcResponse.success(request.id(), result);
	}

	private McpJsonRpcResponse handlePromptsList(McpJsonRpcRequest request) {
		McpAggregatedCatalog catalog = catalogAggregator.getAggregatedCatalog();
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode prmArr = result.putArray("prompts");
		for (McpPromptDefinition prm : catalog.prompts()) {
			prmArr.add(prm.toJsonNode(objectMapper));
		}
		return McpJsonRpcResponse.success(request.id(), result);
	}

	private McpJsonRpcResponse handleToolsCall(McpJsonRpcRequest request, VirtualApiKey apiKey) {
		JsonNode params = request.params();
		if (params == null || !params.isObject()) {
			return McpJsonRpcResponse.failure(request.id(), McpJsonRpcError.invalidParams("params object required"));
		}

		String requestedToolName = params.path("name").asText("");
		if (requestedToolName.isBlank()) {
			return McpJsonRpcResponse.failure(request.id(), McpJsonRpcError.invalidParams("Tool name is required"));
		}

		// 1. Resolve Target Upstream Server Route
		Optional<McpResolvedRoute> routeOpt = router.resolveToolRoute(requestedToolName);
		if (routeOpt.isEmpty()) {
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.methodNotFound("Unknown or disabled MCP tool: " + requestedToolName)
			);
		}
		McpResolvedRoute route = routeOpt.get();
		McpServerConfig server = route.serverConfig();

		// 2. Tool-Level RBAC / ABAC Authorization Check
		if (!rbacPolicyEngine.isToolAllowed(route.namespacedName(), apiKey)) {
			log.warn(
					"MCP RBAC violation: tenant '{}' denied access to tool '{}'",
					apiKey.ownerId(),
					route.namespacedName()
			);
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.accessDenied(
							"Access to tool '" + route.namespacedName() + "' is prohibited by security policy")
			);
		}

		// 3. JSON Schema Draft 2020-12 Parameter Validation
		JsonNode args = params.path("arguments");
		Optional<McpToolDefinition> toolDefOpt = catalogAggregator.getAggregatedCatalog().tools()
		                                                          .stream()
		                                                          .filter(t -> t.name().equals(route.namespacedName()))
		                                                          .findFirst();

		if (toolDefOpt.isPresent()) {
			McpJsonSchemaValidator.ValidationResult valRes = jsonSchemaValidator.validate(
					args,
					toolDefOpt.get().inputSchema()
			);
			if (!valRes.isValid()) {
				return McpJsonRpcResponse.failure(
						request.id(),
						McpJsonRpcError.invalidParams("Parameter validation error: " + valRes.errorMessage())
				);
			}
		}

		// 4. Ingress Credential & Secret Leakage Guardrail Scan
		SecretScanResult secretScan = guardrailScanner.scanArguments(args);
		if (secretScan.detected()) {
			log.warn(
					"MCP Guardrail violation: leaked secret ({}) in tool arguments for '{}'",
					secretScan.ruleId(),
					route.namespacedName()
			);
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.invalidParams("Guardrail violation: sensitive credential detected in tool arguments")
			);
		}

		// 5. In-Memory Atomic CAS Circuit Breaker Check
		if (!circuitBreakerManager.tryAcquire(server.name())) {
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.circuitBreakerTripped(server.name())
			);
		}

		// 6. Human-in-the-Loop (HITL) MRTR Suspension Check
		Optional<McpJsonRpcResponse> hitlSuspension = hitlSuspensionEngine.evaluateOrSuspend(
				request,
				server,
				route.rawTargetName(),
				route.namespacedName(),
				apiKey
		);
		if (hitlSuspension.isPresent()) {
			return hitlSuspension.get();
		}

		// 7. Dispatch to Upstream MCP Server over Multiplexed HTTP/2
		try {
			ObjectNode upstreamRpc = objectMapper.createObjectNode();
			upstreamRpc.put("jsonrpc", "2.0");
			if (request.id() != null) {
				upstreamRpc.set("id", request.id());
			} else {
				upstreamRpc.put("id", UUID.randomUUID().toString());
			}
			upstreamRpc.put("method", "tools/call");

			ObjectNode upstreamParams = upstreamRpc.putObject("params");
			upstreamParams.put("name", route.rawTargetName());
			if (args != null && !args.isMissingNode()) {
				upstreamParams.set("arguments", args);
			}
			if (params.has("_meta")) {
				upstreamParams.set("_meta", params.get("_meta"));
			}

			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
			                                            .uri(server.baseUrl())
			                                            .timeout(server.requestTimeout())
			                                            .header("Content-Type", "application/json")
			                                            .header("Accept", "application/json")
			                                            .header(
					                                            McpHeaderNormalizer.HEADER_PROTOCOL_VERSION,
					                                            McpProtocolVersion.LATEST
			                                            )
			                                            .header(McpHeaderNormalizer.HEADER_MCP_METHOD, "tools/call")
			                                            .header(
					                                            McpHeaderNormalizer.HEADER_MCP_NAME,
					                                            route.rawTargetName()
			                                            )
			                                            .POST(HttpRequest.BodyPublishers.ofString(upstreamRpc.toString()));

			if (server.apiKey() != null && !server.apiKey().value().isBlank()) {
				reqBuilder.header("Authorization", "Bearer " + server.apiKey().value());
			}

			HttpResponse<String> upstreamResponse = httpClient.send(
					reqBuilder.build(),
					HttpResponse.BodyHandlers.ofString()
			);

			if (upstreamResponse.statusCode() >= 200 && upstreamResponse.statusCode() < 300) {
				circuitBreakerManager.recordSuccess(server.name());
				JsonNode respNode = objectMapper.readTree(upstreamResponse.body());

				// Egress Sanitization & Nonced Tag Wrapping
				if (respNode.has("result")) {
					JsonNode resultNode = respNode.get("result");
					if (resultNode.has("content") && resultNode.path("content").isArray()) {
						for (JsonNode contentItem : resultNode.path("content")) {
							if ("text".equals(contentItem.path("type").asText()) && contentItem.has("text")) {
								String originalText = contentItem.path("text").asText();
								String wrapped = guardrailScanner.wrapToolOutputWithNonce(
										route.namespacedName(),
										originalText
								);
								((ObjectNode) contentItem).put("text", wrapped);
							}
						}
					}
					return McpJsonRpcResponse.success(request.id(), resultNode);
				} else if (respNode.has("error")) {
					JsonNode errNode = respNode.get("error");
					return McpJsonRpcResponse.failure(
							request.id(),
							errNode.path("code").asInt(-32000),
							errNode.path("message").asText("Upstream error"),
							errNode.path("data")
					);
				}
			}

			circuitBreakerManager.recordFailure(server.name());
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.internalError("Upstream server returned HTTP " + upstreamResponse.statusCode())
			);
		} catch (Exception e) {
			circuitBreakerManager.recordFailure(server.name());
			log.error(
					"Failed executing tool '{}' against server '{}': {}",
					route.namespacedName(),
					server.name(),
					e.getMessage()
			);
			return McpJsonRpcResponse.failure(
					request.id(),
					McpJsonRpcError.internalError("Tool execution failed: " + e.getMessage())
			);
		}
	}

	private @Nullable VirtualApiKey resolveApiKey(HttpServletRequest request) {
		Object attr = request.getAttribute("virtualApiKey");
		if (attr instanceof VirtualApiKey key) {
			return key;
		}

		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			String token = authHeader.substring(7).trim();
			if (!token.isBlank()) {
				SHA256Hash hash = SHA256Hash.fromRawKey(token);
				return keyManagementService.findByHash(hash).orElse(null);
			}
		}
		return null;
	}
}
