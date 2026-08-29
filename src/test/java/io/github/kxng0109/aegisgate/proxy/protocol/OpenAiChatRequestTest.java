package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiChatRequest} parsing and helpers.
 */
@DisplayName("OpenAiChatRequest")
class OpenAiChatRequestTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("unknown fields are ignored during parsing")
	void ignoresUnknownFields() throws Exception {
		OpenAiChatRequest request = objectMapper.readValue(
				"{\"model\":\"m\",\"messages\":[],\"brand_new_field\":123}", OpenAiChatRequest.class);
		assertEquals("m", request.model());
		assertTrue(request.messages().isEmpty());
	}

	@Test
	@DisplayName("reports whether the client asked for usage")
	void reportsUsageRequest() throws Exception {
		assertTrue(parse("{\"model\":\"m\",\"stream_options\":{\"include_usage\":true}}").requestsUsage());
		assertFalse(parse("{\"model\":\"m\",\"stream_options\":{\"include_usage\":false}}").requestsUsage());
		assertFalse(parse("{\"model\":\"m\"}").requestsUsage());
		assertFalse(parse("{\"model\":\"m\",\"stream_options\":42}").requestsUsage());
	}

	@Test
	@DisplayName("prefers max_completion_tokens as the effective bound")
	void effectiveMaxTokens() throws Exception {
		assertEquals(250, parse("{\"model\":\"m\",\"max_tokens\":100,\"max_completion_tokens\":250}").effectiveMaxTokens());
		assertEquals(100, parse("{\"model\":\"m\",\"max_tokens\":100}").effectiveMaxTokens());
		assertNull(parse("{\"model\":\"m\"}").effectiveMaxTokens());
	}

	private static OpenAiChatRequest parse(String body) throws Exception {
		return new ObjectMapper().readValue(body, OpenAiChatRequest.class);
	}
}