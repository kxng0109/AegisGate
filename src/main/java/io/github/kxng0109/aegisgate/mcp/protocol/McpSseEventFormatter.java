package io.github.kxng0109.aegisgate.mcp.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * High-performance, zero-allocation Server-Sent Events (SSE) formatter for Model Context Protocol streams.
 */
public final class McpSseEventFormatter {

	private static final byte[] EVENT_MESSAGE_PREFIX = "event: message\ndata: ".getBytes(StandardCharsets.UTF_8);
	private static final byte[] EVENT_ENDPOINT_PREFIX = "event: endpoint\ndata: ".getBytes(StandardCharsets.UTF_8);
	private static final byte[] DOUBLE_NEWLINE = "\n\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[] KEEP_ALIVE_COMMENT = ":\r\n\r\n".getBytes(StandardCharsets.UTF_8);

	private McpSseEventFormatter() {
	}

	/**
	 * Formats a JSON-RPC payload as an SSE {@code message} event and writes it to the output stream.
	 *
	 * @param jsonPayload UTF-8 encoded JSON string
	 * @param out         destination stream
	 * @throws IOException if I/O error occurs
	 */
	public static void writeMessageEvent(String jsonPayload, OutputStream out) throws IOException {
		out.write(EVENT_MESSAGE_PREFIX);
		out.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
		out.write(DOUBLE_NEWLINE);
		out.flush();
	}

	/**
	 * Formats an initial endpoint URI as an SSE {@code endpoint} event for legacy 2024-11-05 clients.
	 *
	 * @param endpointUri relative or absolute message endpoint URI
	 * @param out         destination stream
	 * @throws IOException if I/O error occurs
	 */
	public static void writeEndpointEvent(String endpointUri, OutputStream out) throws IOException {
		out.write(EVENT_ENDPOINT_PREFIX);
		out.write(endpointUri.getBytes(StandardCharsets.UTF_8));
		out.write(DOUBLE_NEWLINE);
		out.flush();
	}

	/**
	 * Writes a standard SSE keep-alive comment to maintain active HTTP/2 and TCP connections.
	 *
	 * @param out destination stream
	 * @throws IOException if I/O error occurs
	 */
	public static void writeKeepAlive(OutputStream out) throws IOException {
		out.write(KEEP_ALIVE_COMMENT);
		out.flush();
	}
}
