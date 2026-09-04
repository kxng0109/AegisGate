package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Metadata definition for an MCP prompt template.
 *
 * @param name        unique prompt identifier
 * @param description optional prompt description
 * @param arguments   list of argument descriptors
 * @param meta        optional metadata object
 */
public record McpPromptDefinition(
		String name,
		@Nullable String description,
		List<McpPromptArgument> arguments,
		@Nullable JsonNode meta
) {
	public McpPromptDefinition {
		arguments = arguments == null ? List.of() : List.copyOf(arguments);
	}

	public ObjectNode toJsonNode(ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		node.put("name", name);
		if (description != null) {
			node.put("description", description);
		}
		if (!arguments.isEmpty()) {
			ArrayNode argsArr = node.putArray("arguments");
			for (McpPromptArgument arg : arguments) {
				argsArr.add(arg.toJsonNode(mapper));
			}
		}
		if (meta != null) {
			node.set("_meta", meta);
		}
		return node;
	}
}
