package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Metadata definition for an executable Model Context Protocol (MCP) tool.
 *
 * @param name        unique tool identifier (may be namespaced)
 * @param description human and model readable description of tool capabilities
 * @param inputSchema JSON Schema Draft 2020-12 specification of tool arguments
 * @param meta        optional metadata object
 */
public record McpToolDefinition(
		String name,
		@Nullable String description,
		@Nullable JsonNode inputSchema,
		@Nullable JsonNode meta
) {
	public ObjectNode toJsonNode(ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		node.put("name", name);
		if (description != null) {
			node.put("description", description);
		}
		if (inputSchema != null) {
			node.set("inputSchema", inputSchema);
		} else {
			ObjectNode emptySchema = mapper.createObjectNode();
			emptySchema.put("type", "object");
			node.set("inputSchema", emptySchema);
		}
		if (meta != null) {
			node.set("_meta", meta);
		}
		return node;
	}
}
