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

	// MCP-Specific Error Code Partition (-32000 to -32099)
	public static final int SERVER_ERROR = -32000;
	public static final int RESOURCE_NOT_FOUND = -32602;
	public static final int HEADER_MISMATCH = -32020;
	public static final int MISSING_REQUIRED_CAPABILITY = -32021;
	public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;
	public static final int HITL_SUSPENDED = -32023;
	public static final int CIRCUIT_BREAKER_TRIPPED = -32024;
	public static final int ACCESS_DENIED = -32025;

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

	public static McpJsonRpcError accessDenied(String detail) {
		return new McpJsonRpcError(ACCESS_DENIED, "Access denied: " + detail, null);
	}

	public static McpJsonRpcError circuitBreakerTripped(String serverName) {
		return new McpJsonRpcError(
				CIRCUIT_BREAKER_TRIPPED,
				"Upstream MCP server '" + serverName + "' is temporarily unavailable (circuit breaker open)",
				null
		);
	}
}
