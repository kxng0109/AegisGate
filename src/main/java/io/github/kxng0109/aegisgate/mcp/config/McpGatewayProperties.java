package io.github.kxng0109.aegisgate.mcp.config;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.contracts.McpProtocolVersion;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for the Model Context Protocol (MCP) Security & Tool Governance Gateway.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.mcp")
public class McpGatewayProperties {

	/**
	 * Master toggle for the MCP gateway subsystem.
	 */
	private boolean enabled = true;

	/**
	 * Registered upstream MCP servers, keyed by server name/prefix.
	 */
	private Map<String, McpServerConfig> servers = new LinkedHashMap<>();

	/**
	 * Default MCP protocol version for clients omitting explicit version headers.
	 */
	private String defaultProtocolVersion = McpProtocolVersion.LATEST;

	/**
	 * In-memory L0 Caffeine cache TTL for aggregated tool catalogs.
	 */
	private Duration catalogCacheTtl = Duration.ofMinutes(5);

	/**
	 * Background catalog refresh interval / cron.
	 */
	private String catalogRefreshCron = "0 */5 * * * *";

	/**
	 * Expiration TTL for Human-in-the-Loop (HITL) resumption tokens.
	 */
	private Duration hitlSuspensionTtl = Duration.ofSeconds(300);

	/**
	 * 256-bit secret key used for AEAD encryption of HITL resumption tokens.
	 */
	private SensitiveString hitlSecret = new SensitiveString("aegisgate-default-mcp-hitl-secret-key-32bytes!!");

	/**
	 * Maximum permissible byte length for a single incoming or outgoing MCP SSE message.
	 */
	private int maxSseMessageBytes = 2 * 1024 * 1024; // 2 MB

	/**
	 * Whether to enable backwards-compatible legacy dual-endpoint SSE transport (GET /mcp/sse + POST /mcp/message).
	 */
	private boolean allowLegacySse = true;

	/**
	 * Upstream MCP server circuit breaker failure threshold.
	 */
	private int circuitBreakerFailureThreshold = 3;

	/**
	 * Upstream MCP server circuit breaker cooldown duration.
	 */
	private Duration circuitBreakerCooldown = Duration.ofSeconds(30);

	/**
	 * Upstream HTTP client connect timeout.
	 */
	private Duration clientConnectTimeout = Duration.ofSeconds(5);

	/**
	 * Sets the registered upstream MCP servers.
	 */
	public void setServers(Map<String, McpServerConfig> servers) {
		this.servers = servers == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(servers));
	}
}
