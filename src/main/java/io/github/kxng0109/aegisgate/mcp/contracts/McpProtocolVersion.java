package io.github.kxng0109.aegisgate.mcp.contracts;

import java.util.List;
import java.util.Set;

/**
 * Model Context Protocol (MCP) version constants and compatibility negotiation helpers.
 */
public final class McpProtocolVersion {

	public static final String V2026_07_28 = "2026-07-28";
	public static final String V2025_11_25 = "2025-11-25";
	public static final String V2024_11_05 = "2024-11-05";

	public static final String LATEST = V2026_07_28;

	public static final Set<String> SUPPORTED_VERSIONS = Set.of(
			V2026_07_28,
			V2025_11_25,
			V2024_11_05
	);

	public static final List<String> SUPPORTED_VERSIONS_ORDERED = List.of(
			V2026_07_28,
			V2025_11_25,
			V2024_11_05
	);

	private McpProtocolVersion() {
	}

	/**
	 * Checks whether the given protocol version is supported by the gateway.
	 *
	 * @param version candidate version string
	 * @return true if supported
	 */
	public static boolean isSupported(String version) {
		if (version == null || version.isBlank()) {
			return false;
		}
		return SUPPORTED_VERSIONS.contains(version.trim());
	}
}
