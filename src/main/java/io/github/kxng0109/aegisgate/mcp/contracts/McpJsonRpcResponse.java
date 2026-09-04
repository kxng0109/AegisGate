package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable representation of a JSON-RPC 2.0 response in the Model Context Protocol (MCP).
 *
 * @param jsonrpc protocol indicator (must be "2.0")
 * @param id      matching request identifier
 * @param result  successful execution payload (mutually exclusive with error)
 * @param error   failure error object (mutually exclusive with result)
 */
public record McpJsonRpcResponse(
		String jsonrpc,
		@Nullable JsonNode id,
		@Nullable JsonNode result,
		@Nullable McpJsonRpcError error
) {
	public McpJsonRpcResponse {
		jsonrpc = (jsonrpc == null || jsonrpc.isBlank()) ? "2.0" : jsonrpc;
	}

	public static McpJsonRpcResponse success(@Nullable JsonNode id, JsonNode result) {
		return new McpJsonRpcResponse("2.0", id, result, null);
	}

	public static McpJsonRpcResponse failure(@Nullable JsonNode id, McpJsonRpcError error) {
		return new McpJsonRpcResponse("2.0", id, null, error);
	}

	public static McpJsonRpcResponse failure(@Nullable JsonNode id, int code, String message, @Nullable JsonNode data) {
		return new McpJsonRpcResponse("2.0", id, null, new McpJsonRpcError(code, message, data));
	}

	public boolean isSuccess() {
		return error == null;
	}

	public ObjectNode toJsonNode(ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		node.put("jsonrpc", "2.0");
		if (id != null) {
			node.set("id", id);
		} else {
			node.putNull("id");
		}
		if (error != null) {
			ObjectNode errObj = node.putObject("error");
			errObj.put("code", error.code());
			errObj.put("message", error.message());
			if (error.data() != null) {
				errObj.set("data", error.data());
			}
		} else if (result != null) {
			node.set("result", result);
		} else {
			node.putObject("result");
		}
		return node;
	}
}
