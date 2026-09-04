package io.github.kxng0109.aegisgate.mcp.security;

import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool-level RBAC/ABAC authorization engine for Model Context Protocol (MCP) tool execution and catalog filtering.
 */
@Component
public class McpToolRbacPolicyEngine {

	/**
	 * Checks whether a virtual API key has permission to invoke a specific tool.
	 *
	 * @param toolName namespaced tool name (e.g. {@code postgres__run_query})
	 * @param apiKey   authenticated virtual API key
	 * @return true if authorized, false if denied
	 */
	public boolean isToolAllowed(String toolName, VirtualApiKey apiKey) {
		if (toolName == null || toolName.isBlank() || apiKey == null) {
			return false;
		}
		String target = toolName.trim();

		// 1. Deny list evaluation takes absolute precedence
		Set<String> denied = apiKey.deniedTools();
		if (denied != null && !denied.isEmpty()) {
			for (String pattern : denied) {
				if (matchesPattern(target, pattern)) {
					return false;
				}
			}
		}

		// 2. Allow list evaluation (empty set = permit all)
		Set<String> allowed = apiKey.allowedTools();
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}

		for (String pattern : allowed) {
			if (matchesPattern(target, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Filters the federated MCP catalog, pruning any tools that the virtual key is not authorized to access.
	 *
	 * @param catalog global aggregated catalog
	 * @param apiKey  authenticated virtual API key
	 * @return filtered catalog containing only permitted tools
	 */
	public McpAggregatedCatalog filterCatalog(McpAggregatedCatalog catalog, VirtualApiKey apiKey) {
		if (catalog == null) {
			return McpAggregatedCatalog.empty();
		}
		if (apiKey == null) {
			return new McpAggregatedCatalog(List.of(), catalog.resources(), catalog.prompts(), catalog.fetchedAt());
		}

		List<McpToolDefinition> authorizedTools = catalog.tools()
		                                                 .stream()
		                                                 .filter(t -> isToolAllowed(t.name(), apiKey))
		                                                 .toList();

		return new McpAggregatedCatalog(
				authorizedTools,
				catalog.resources(),
				catalog.prompts(),
				catalog.fetchedAt()
		);
	}

	/**
	 * Evaluates standard glob pattern syntax (* and ?).
	 */
	public static boolean matchesPattern(String text, String globPattern) {
		if (globPattern == null || globPattern.isBlank()) {
			return false;
		}
		String trimmedGlob = globPattern.trim();
		if ("*".equals(trimmedGlob)) {
			return true;
		}
		if (text.equals(trimmedGlob)) {
			return true;
		}

		// Convert glob to regex
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < trimmedGlob.length(); i++) {
			char c = trimmedGlob.charAt(i);
			switch (c) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append(".");
				case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> {
					regex.append("\\").append(c);
				}
				default -> regex.append(c);
			}
		}
		regex.append("$");
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(text).matches();
	}
}
