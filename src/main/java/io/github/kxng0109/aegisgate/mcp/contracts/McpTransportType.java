package io.github.kxng0109.aegisgate.mcp.contracts;

/**
 * Supported underlying transport protocols for Model Context Protocol (MCP) server communication.
 */
public enum McpTransportType {
	/**
	 * Stateless Streamable HTTP transport (MCP 2026-07-28+) over unified POST /mcp endpoint.
	 */
	STREAMABLE_HTTP,

	/**
	 * Dual-endpoint HTTP with Server-Sent Events (GET /sse + POST /message) transport (legacy 2024-11-05).
	 */
	HTTP_SSE,

	/**
	 * Standard I/O subprocess transport via stdin/stdout pipe.
	 */
	STDIO
}
