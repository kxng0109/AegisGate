package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.mcp.contracts.McpPromptDefinition;
import io.github.kxng0109.aegisgate.mcp.contracts.McpResourceDefinition;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;

import java.time.Instant;
import java.util.List;

/**
 * Immutable federated catalog snapshot aggregated across all registered upstream MCP servers.
 *
 * @param tools     federated list of tool definitions with server namespacing
 * @param resources federated list of resource definitions
 * @param prompts   federated list of prompt templates
 * @param fetchedAt timestamp when this catalog was aggregated
 */
public record McpAggregatedCatalog(
		List<McpToolDefinition> tools,
		List<McpResourceDefinition> resources,
		List<McpPromptDefinition> prompts,
		Instant fetchedAt
) {
	public McpAggregatedCatalog {
		tools = tools == null ? List.of() : List.copyOf(tools);
		resources = resources == null ? List.of() : List.copyOf(resources);
		prompts = prompts == null ? List.of() : List.copyOf(prompts);
	}

	public static McpAggregatedCatalog empty() {
		return new McpAggregatedCatalog(List.of(), List.of(), List.of(), Instant.now());
	}
}
