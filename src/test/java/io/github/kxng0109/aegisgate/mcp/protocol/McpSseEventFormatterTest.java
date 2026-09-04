package io.github.kxng0109.aegisgate.mcp.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP SSE Event Formatter Unit Tests")
class McpSseEventFormatterTest {

	@Test
	@DisplayName("writeMessageEvent formats JSON-RPC payloads as SSE message events")
	void writeMessageEvent() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}";

		McpSseEventFormatter.writeMessageEvent(payload, out);

		String result = out.toString(StandardCharsets.UTF_8);
		assertThat(result).isEqualTo("event: message\ndata: " + payload + "\n\n");
	}

	@Test
	@DisplayName("writeEndpointEvent formats initial legacy endpoint discovery events")
	void writeEndpointEvent() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String uri = "/v1/mcp/message?sessionId=abc-123";

		McpSseEventFormatter.writeEndpointEvent(uri, out);

		String result = out.toString(StandardCharsets.UTF_8);
		assertThat(result).isEqualTo("event: endpoint\ndata: /v1/mcp/message?sessionId=abc-123\n\n");
	}

	@Test
	@DisplayName("writeKeepAlive formats standard SSE comment frames")
	void writeKeepAlive() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		McpSseEventFormatter.writeKeepAlive(out);

		String result = out.toString(StandardCharsets.UTF_8);
		assertThat(result).isEqualTo(":\r\n\r\n");
	}
}
