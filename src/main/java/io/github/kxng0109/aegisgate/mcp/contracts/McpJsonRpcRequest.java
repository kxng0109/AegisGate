package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Immutable representation of a JSON-RPC 2.0 request or notification in the Model Context Protocol (MCP).
 *
 * @param jsonrpc protocol indicator (must be "2.0")
 * @param id      request identifier (null for one-way notifications)
 * @param method  invoked MCP method (e.g. tools/list, tools/call, resources/read, ping)
 * @param params  parameters payload object or array
 */
public record McpJsonRpcRequest(
		String jsonrpc,
		@Nullable JsonNode id,
		String method,
		@Nullable JsonNode params
) {
	public McpJsonRpcRequest {
		jsonrpc = (jsonrpc == null || jsonrpc.isBlank()) ? "2.0" : jsonrpc;
	}

	/**
	 * Returns true if this message is a one-way notification (id is null or missing).
	 */
	public boolean isNotification() {
		return id == null || id.isNull();
	}

	/**
	 * Resolves the client protocol version from params._meta."io.modelcontextprotocol/protocolVersion" if present.
	 */
	public @Nullable String resolveProtocolVersion() {
		if (params == null || !params.has("_meta")) {
			return null;
		}
		JsonNode meta = params.path("_meta");
		if (meta.has("io.modelcontextprotocol/protocolVersion")) {
			return meta.path("io.modelcontextprotocol/protocolVersion").asString(null);
		}
		if (meta.has("protocolVersion")) {
			return meta.path("protocolVersion").asString(null);
		}
		return null;
	}

	/**
	 * Resolves the progress token from params._meta.progressToken if present.
	 */
	public @Nullable String resolveProgressToken() {
		if (params == null || !params.has("_meta")) {
			return null;
		}
		JsonNode meta = params.path("_meta");
		if (meta.has("progressToken")) {
			return meta.path("progressToken").asString(null);
		}
		return null;
	}
}
