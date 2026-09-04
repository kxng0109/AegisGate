package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;

/**
 * Resolved routing destination for an MCP tool, resource, or prompt invocation.
 *
 * @param serverConfig   target upstream server configuration
 * @param rawTargetName  un-prefixed target name to send to upstream server (e.g. {@code run_query})
 * @param namespacedName full federated name presented to the client (e.g. {@code postgres__run_query})
 */
public record McpResolvedRoute(
		McpServerConfig serverConfig,
		String rawTargetName,
		String namespacedName
) {
}
