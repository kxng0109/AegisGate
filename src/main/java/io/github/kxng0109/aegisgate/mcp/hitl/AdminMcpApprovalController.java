package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.TimeUnit;

/**
 * REST controller for Human-in-the-Loop (HITL) administrator inspection, approval, and rejection of suspended tool
 * calls.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/mcp/approvals")
@RequiredArgsConstructor
@Tag(name = "Admin - MCP Tool Governance", description = "Human-in-the-Loop (HITL) tool execution review, consent, and approval management")
public class AdminMcpApprovalController {

	private static final String REDIS_PENDING_PREFIX = "mcp:hitl:pending:";
	private static final String REDIS_APPROVED_PREFIX = "mcp:hitl:approved:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Inspects pending tool invocation metadata for a specific token ID.
	 */
	@Operation(
			summary = "Get pending MCP tool invocation details",
			description = "Retrieves the parameters, tenant, and tool metadata for a suspended tool execution awaiting approval.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pending invocation details retrieved"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found or expired"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key required")
	})
	@GetMapping(value = "/{tokenId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getPendingApproval(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId
	) {
		String raw = redisTemplate.opsForValue().get(REDIS_PENDING_PREFIX + tokenId);
		if (raw == null || raw.isBlank()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(raw);
	}

	/**
	 * Approves a suspended tool invocation, clearing it for execution on subsequent retry.
	 */
	@Operation(
			summary = "Approve suspended MCP tool invocation",
			description = "Marks the suspended tool invocation as APPROVED in Redis. When the client retries with the resumption token, execution proceeds.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invocation successfully approved"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found or expired"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping(value = "/{tokenId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> approveToolCall(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId
	) {
		String pendingKey = REDIS_PENDING_PREFIX + tokenId;
		String raw = redisTemplate.opsForValue().get(pendingKey);
		if (raw == null || raw.isBlank()) {
			return ResponseEntity.notFound().build();
		}

		// Mark approved with a 300s window
		redisTemplate.opsForValue().set(REDIS_APPROVED_PREFIX + tokenId, "APPROVED", 300, TimeUnit.SECONDS);
		log.info("Administrator approved MCP tool invocation for token ID '{}'", tokenId);

		ObjectNode response = objectMapper.createObjectNode();
		response.put("status", "APPROVED");
		response.put("tokenId", tokenId);
		response.put("message", "Tool invocation approved. Client may resume execution.");

		return ResponseEntity.ok(response.toString());
	}

	/**
	 * Rejects and deletes a suspended tool invocation.
	 */
	@Operation(
			summary = "Reject suspended MCP tool invocation",
			description = "Rejects and removes the suspended tool invocation from Redis.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invocation rejected and purged"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping(value = "/{tokenId}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> rejectToolCall(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId
	) {
		redisTemplate.delete(REDIS_PENDING_PREFIX + tokenId);
		redisTemplate.delete(REDIS_APPROVED_PREFIX + tokenId);
		log.info("Administrator rejected MCP tool invocation for token ID '{}'", tokenId);

		ObjectNode response = objectMapper.createObjectNode();
		response.put("status", "REJECTED");
		response.put("tokenId", tokenId);
		response.put("message", "Tool invocation rejected and purged.");

		return ResponseEntity.ok(response.toString());
	}
}
