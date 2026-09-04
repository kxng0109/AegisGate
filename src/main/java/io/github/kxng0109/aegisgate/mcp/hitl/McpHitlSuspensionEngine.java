package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.aegisgate.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Multi Round-Trip Request (MRTR, SEP-2322) Human-in-the-Loop (HITL) suspension and resumption engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpHitlSuspensionEngine {

	private static final String REDIS_PENDING_PREFIX = "mcp:hitl:pending:";
	private static final String REDIS_APPROVED_PREFIX = "mcp:hitl:approved:";

	private final McpGatewayProperties properties;
	private final McpAeadResumptionTokenService tokenService;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Checks whether this tool requires Human-in-the-Loop approval per server configuration.
	 */
	public boolean isHitlRequired(McpServerConfig serverConfig, String rawToolName, String namespacedToolName) {
		Set<String> hitlTools = serverConfig.hitlRequiredTools();
		if (hitlTools == null || hitlTools.isEmpty()) {
			return false;
		}
		for (String pattern : hitlTools) {
			if (McpToolRbacPolicyEngine.matchesPattern(rawToolName, pattern)
					|| McpToolRbacPolicyEngine.matchesPattern(namespacedToolName, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Evaluates an incoming tool invocation under HITL policies.
	 *
	 * @param request            incoming JSON-RPC request
	 * @param serverConfig       target server configuration
	 * @param rawToolName        un-prefixed tool name
	 * @param namespacedToolName federated tool name
	 * @param apiKey             authenticated virtual key
	 * @return optional suspension response if execution is paused; empty if cleared for execution
	 */
	public Optional<McpJsonRpcResponse> evaluateOrSuspend(
			McpJsonRpcRequest request,
			McpServerConfig serverConfig,
			String rawToolName,
			String namespacedToolName,
			VirtualApiKey apiKey
	) {
		if (!isHitlRequired(serverConfig, rawToolName, namespacedToolName)) {
			return Optional.empty();
		}

		JsonNode params = request.params();
		JsonNode args = params != null ? params.path("arguments") : null;
		String serializedArgs = args != null && !args.isMissingNode() ? args.toString() : "{}";
		String currentArgsSha256 = McpAeadResumptionTokenService.computeArgsSha256(serializedArgs);

		String resumptionToken = extractResumptionToken(request);

		if (resumptionToken != null) {
			Optional<McpResumptionClaims> verifiedClaims = tokenService.verifyAndExtract(
					resumptionToken,
					currentArgsSha256,
					apiKey.ownerId()
			);

			if (verifiedClaims.isPresent()) {
				McpResumptionClaims claims = verifiedClaims.get();
				String approvalKey = REDIS_APPROVED_PREFIX + claims.tokenId();
				String approvalStatus = redisTemplate.opsForValue().get(approvalKey);

				if ("APPROVED".equalsIgnoreCase(approvalStatus)) {
					log.info(
							"HITL approval verified for token '{}' on tool '{}'",
							claims.tokenId(),
							namespacedToolName
					);
					// Single-use token consumption (replay protection)
					redisTemplate.delete(approvalKey);
					redisTemplate.delete(REDIS_PENDING_PREFIX + claims.tokenId());
					return Optional.empty(); // Cleared to execute!
				}
			}
		}

		// Tool requires HITL and is not yet approved -> Suspend execution!
		String tokenId = UUID.randomUUID().toString().replace("-", "");
		Instant now = Instant.now();
		Instant expiresAt = now.plus(properties.getHitlSuspensionTtl());

		McpResumptionClaims newClaims = new McpResumptionClaims(
				tokenId,
				apiKey.ownerId(),
				namespacedToolName,
				currentArgsSha256,
				now,
				expiresAt
		);

		String mintedToken = tokenService.mintToken(newClaims);

		// Store pending invocation metadata in Redis for admin review UI
		try {
			ObjectNode pendingMeta = objectMapper.createObjectNode();
			pendingMeta.put("tokenId", tokenId);
			pendingMeta.put("ownerId", apiKey.ownerId());
			pendingMeta.put("keyName", apiKey.name());
			pendingMeta.put("toolName", namespacedToolName);
			pendingMeta.put("serverName", serverConfig.name());
			pendingMeta.put("args", serializedArgs);
			pendingMeta.put("createdAt", now.toString());
			pendingMeta.put("expiresAt", expiresAt.toString());

			long ttlSeconds = properties.getHitlSuspensionTtl().toSeconds();
			redisTemplate.opsForValue().set(
					REDIS_PENDING_PREFIX + tokenId,
					objectMapper.writeValueAsString(pendingMeta),
					ttlSeconds,
					TimeUnit.SECONDS
			);
		} catch (Exception e) {
			log.warn("Failed to persist pending HITL metadata in Redis: {}", e.getMessage());
		}

		// Construct standard MCP InputRequiredResult (MRTR pattern)
		ObjectNode resultNode = objectMapper.createObjectNode();
		resultNode.put("resultType", "input_required");
		resultNode.put("requestState", mintedToken);

		ObjectNode inputRequests = resultNode.putObject("inputRequests");
		ObjectNode hitlRequest = inputRequests.putObject("human_approval");
		hitlRequest.put("method", "elicitation/create");

		ObjectNode hitlParams = hitlRequest.putObject("params");
		hitlParams.put("mode", "url");
		hitlParams.put("url", "/v1/admin/mcp/approvals/" + tokenId);
		hitlParams.put(
				"message",
				"Execution of privileged tool '" + namespacedToolName + "' requires administrator approval."
		);

		return Optional.of(McpJsonRpcResponse.success(request.id(), resultNode));
	}

	private @org.jspecify.annotations.Nullable String extractResumptionToken(McpJsonRpcRequest request) {
		JsonNode params = request.params();
		if (params == null) {
			return null;
		}
		if (params.has("requestState") && params.path("requestState").isTextual()) {
			return params.path("requestState").asText();
		}
		JsonNode meta = params.path("_meta");
		if (meta.has("requestState") && meta.path("requestState").isTextual()) {
			return meta.path("requestState").asText();
		}
		return null;
	}
}
