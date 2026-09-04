package io.github.kxng0109.aegisgate.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Argument descriptor for an MCP prompt template.
 *
 * @param name        argument identifier
 * @param description optional argument description
 * @param required    whether the argument must be supplied
 */
public record McpPromptArgument(
		String name,
		@Nullable String description,
		boolean required
) {
	public ObjectNode toJsonNode(ObjectMapper mapper) {
		ObjectNode node = mapper.createObjectNode();
		node.put("name", name);
		if (description != null) {
			node.put("description", description);
		}
		node.put("required", required);
		return node;
	}
}
