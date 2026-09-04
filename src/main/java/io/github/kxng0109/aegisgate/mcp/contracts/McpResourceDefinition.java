package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Metadata definition for an accessible Model Context Protocol (MCP) contextual resource.
 *
 * @param uri         unique URI identifying the resource (e.g. postgres://table/schema)
 * @param name        human-readable label
 * @param description optional description of the resource content
 * @param mimeType    optional MIME type of the underlying resource (e.g. text/plain, application/json)
 * @param meta        optional metadata object
 */
public record McpResourceDefinition(
		String uri,
		String name,
		@Nullable String description,
		@Nullable String mimeType,
		@Nullable JsonNode meta
) {
	public ObjectNode toJsonNode(ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		node.put("uri", uri);
		node.put("name", name);
		if (description != null) {
			node.put("description", description);
		}
		if (mimeType != null) {
			node.put("mimeType", mimeType);
		}
		if (meta != null) {
			node.set("_meta", meta);
		}
		return node;
	}
}
