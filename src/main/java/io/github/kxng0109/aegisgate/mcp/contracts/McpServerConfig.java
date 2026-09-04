package io.github.kxng0109.aegisgate.mcp.contracts;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Configuration descriptor for an upstream Model Context Protocol (MCP) server.
 *
 * @param name                  unique server prefix/identifier (e.g. {@code postgres}, {@code github})
 * @param transport             underlying transport protocol (defaults to {@link McpTransportType#STREAMABLE_HTTP})
 * @param baseUrl               root URL of the MCP server endpoint
 * @param apiKey                optional upstream authorization secret
 * @param connectTimeout        connection establishment timeout
 * @param requestTimeout        response header / stream execution timeout
 * @param allowedTools          whitelist of tool names/globs from this server (empty = all)
 * @param deniedTools           blacklist of tool names/globs from this server (empty = none)
 * @param hitlRequiredTools     set of tool names/globs requiring Human-in-the-Loop approval
 * @param maxConcurrentRequests max in-flight requests before backpressure gating (0 = unlimited)
 * @param enabled               whether this server is currently active
 */
public record McpServerConfig(
		String name,
		McpTransportType transport,
		URI baseUrl,
		@Nullable SensitiveString apiKey,
		Duration connectTimeout,
		Duration requestTimeout,
		Set<String> allowedTools,
		Set<String> deniedTools,
		Set<String> hitlRequiredTools,
		int maxConcurrentRequests,
		boolean enabled
) {
	public McpServerConfig {
		transport = transport != null ? transport : McpTransportType.STREAMABLE_HTTP;
		connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
		requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(30);
		allowedTools = allowedTools != null ? Set.copyOf(allowedTools) : Set.of();
		deniedTools = deniedTools != null ? Set.copyOf(deniedTools) : Set.of();
		hitlRequiredTools = hitlRequiredTools != null ? Set.copyOf(hitlRequiredTools) : Set.of();
	}
}
