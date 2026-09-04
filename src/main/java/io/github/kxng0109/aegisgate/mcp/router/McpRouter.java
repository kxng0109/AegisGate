package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic namespacing and L7 routing engine for Model Context Protocol (MCP) invocations. Resolves namespaced
 * identifiers (e.g. {@code postgres__execute_query}) to the target upstream server.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRouter {

	public static final String NAMESPACE_DELIMITER = "__";

	private final McpGatewayProperties properties;

	/**
	 * Formats a canonical namespaced tool identifier: {@code <server_id>__<tool_name>}.
	 *
	 * @param serverName unique upstream server identifier
	 * @param toolName   native tool identifier
	 * @return federated namespaced tool identifier
	 */
	public static String formatNamespacedName(String serverName, String toolName) {
		if (serverName == null || serverName.isBlank()) {
			return toolName;
		}
		return serverName.trim() + NAMESPACE_DELIMITER + toolName.trim();
	}

	/**
	 * Resolves a target tool invocation name to its corresponding upstream server configuration and native tool name.
	 *
	 * @param requestedToolName tool identifier requested by the client
	 * @return resolved route, or empty if target server is unknown or inactive
	 */
	public Optional<McpResolvedRoute> resolveToolRoute(String requestedToolName) {
		if (requestedToolName == null || requestedToolName.isBlank()) {
			return Optional.empty();
		}
		String trimmed = requestedToolName.trim();
		int delimiterIdx = trimmed.indexOf(NAMESPACE_DELIMITER);

		Map<String, McpServerConfig> servers = properties.getServers();

		// 1. Explicit namespaced route (e.g. "postgres__run_query")
		if (delimiterIdx > 0 && delimiterIdx < trimmed.length() - 2) {
			String serverPrefix = trimmed.substring(0, delimiterIdx);
			String rawToolName = trimmed.substring(delimiterIdx + 2);
			McpServerConfig config = servers.get(serverPrefix);
			if (config != null && config.enabled()) {
				return Optional.of(new McpResolvedRoute(config, rawToolName, trimmed));
			}
			log.warn("MCP routing failed: server prefix '{}' is unknown or disabled", serverPrefix);
			return Optional.empty();
		}

		// 2. Fallback: single server match if un-namespaced
		List<McpResolvedRoute> candidates = new ArrayList<>();
		for (McpServerConfig config : servers.values()) {
			if (!config.enabled()) {
				continue;
			}
			// If server has explicit allowedTools, check if it contains this tool
			if (config.allowedTools().isEmpty() || config.allowedTools().contains(trimmed)) {
				candidates.add(new McpResolvedRoute(config, trimmed, formatNamespacedName(config.name(), trimmed)));
			}
		}

		if (candidates.size() == 1) {
			return Optional.of(candidates.get(0));
		}
		if (candidates.size() > 1) {
			log.warn("MCP routing collision: un-namespaced tool '{}' matches multiple servers", requestedToolName);
		}
		return Optional.empty();
	}
}
