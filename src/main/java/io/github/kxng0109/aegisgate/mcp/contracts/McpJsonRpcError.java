package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standard JSON-RPC 2.0 error object with Model Context Protocol (MCP) partitioned error codes.
 *
 * @param code    numeric error code
 * @param message human-readable error summary
 * @param data    optional structured error payload
 */
public record McpJsonRpcError(
		int code,
		String message,
		@Nullable JsonNode data
) {
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	// MCP-spec-defined codes in the -32020..-32099 sub-range (MCP 2026-07-28 schema.ts, basic spec).
	// ONLY these three codes may be emitted from this sub-range. Implementations MUST NOT emit any
	// other code from -32020..-32099. Local implementation errors (RBAC denial, circuit-breaker state)
	// are surfaced as INTERNAL_ERROR (-32603), never as reserved-range codes.
	public static final int HEADER_MISMATCH = -32020;
	public static final int MISSING_REQUIRED_CAPABILITY = -32021;
	public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

	public static McpJsonRpcError parseError(String detail) {
		return new McpJsonRpcError(PARSE_ERROR, "Parse error: " + detail, null);
	}

	public static McpJsonRpcError invalidRequest(String detail) {
		return new McpJsonRpcError(INVALID_REQUEST, "Invalid Request: " + detail, null);
	}

	public static McpJsonRpcError methodNotFound(String method) {
		return new McpJsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
	}

	public static McpJsonRpcError invalidParams(String detail) {
		return new McpJsonRpcError(INVALID_PARAMS, "Invalid params: " + detail, null);
	}

	public static McpJsonRpcError internalError(String detail) {
		return new McpJsonRpcError(INTERNAL_ERROR, "Internal error: " + detail, null);
	}

	/**
	 * RBAC policy denial. Surfaced as {@code INTERNAL_ERROR} (-32603) because per-request tool authorization is a local
	 * policy decision, not a protocol error; the MCP spec forbids emitting undefined codes from the -32020..-32099
	 * sub-range.
	 */
	public static McpJsonRpcError accessDenied(String detail) {
		return internalError("Access denied: " + detail);
	}

	/**
	 * Upstream circuit-breaker open. Surfaced as {@code INTERNAL_ERROR} (-32603) because breaker state is a local
	 * implementation error, not a protocol error; the MCP spec forbids emitting undefined codes from the -32020..-32099
	 * sub-range.
	 */
	public static McpJsonRpcError circuitBreakerTripped(String serverName) {
		return internalError("Upstream MCP server '" + serverName
				                     + "' is temporarily unavailable (circuit breaker open)");
	}

	public static McpJsonRpcError headerMismatch(String detail) {
		return new McpJsonRpcError(HEADER_MISMATCH, "Header mismatch: " + detail, null);
	}

	public static McpJsonRpcError unsupportedVersion(String requestedVersion, ObjectMapper mapper) {
		ObjectNode dataNode = mapper.createObjectNode();
		dataNode.put("requested", requestedVersion);
		ArrayNode supportedNode = dataNode.putArray("supported");
		for (String v : McpProtocolVersion.SUPPORTED_VERSIONS_ORDERED) {
			supportedNode.add(v);
		}
		return new McpJsonRpcError(UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", dataNode);
	}
}
