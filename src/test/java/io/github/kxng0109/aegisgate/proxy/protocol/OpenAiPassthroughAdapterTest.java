package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiPassthroughAdapter}: body edits (model override
 * and forced usage reporting), headers, and URL building.
 */
@DisplayName("OpenAiPassthroughAdapter")
class OpenAiPassthroughAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OpenAiPassthroughAdapter adapter = new OpenAiPassthroughAdapter(objectMapper);

	@Test
	@DisplayName("injects include_usage while keeping the body intact")
	void injectsUsageReporting() throws Exception {
		String body = "{\"model\":\"gpt-5.6-luna\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
				+ "\"temperature\":0.7}";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));

		assertEquals("gpt-5.6-luna", result.get("model").asText());
		assertEquals("hi", result.path("messages").get(0).path("content").asText());
		assertEquals(0.7, result.path("temperature").asDouble());
		assertEquals(true, result.path("stream_options").path("include_usage").asBoolean());
	}

	@Test
	@DisplayName("keeps client stream options and forces include_usage")
	void preservesStreamOptions() throws Exception {
		String body = "{\"model\":\"m\",\"stream_options\":{\"include_obfuscation\":false}}";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));

		assertEquals(false, result.path("stream_options").path("include_obfuscation").asBoolean());
		assertEquals(true, result.path("stream_options").path("include_usage").asBoolean());
	}

	@Test
	@DisplayName("applies the model override")
	void appliesModelOverride() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\"}", "gpt-5.6-sol"));
		assertEquals("gpt-5.6-sol", result.get("model").asText());
	}

	@Test
	@DisplayName("builds the chat completions URL from the base")
	void buildsUpstreamUrl() {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create("https://api.openai.com/"), null,
				Duration.ofSeconds(3), Duration.ofSeconds(30));
		assertEquals("https://api.openai.com/v1/chat/completions",
		             adapter.buildUpstreamUrl(config).toString());
	}

	@Test
	@DisplayName("a fresh normalizer is produced per stream")
	void createsFreshNormalizer() {
		SseNormalizer first = adapter.newNormalizer(false, "m");
		SseNormalizer second = adapter.newNormalizer(true, "m");
		assertNotSame(first, second);
		assertInstanceOf(OpenAiSseNormalizer.class, first);
	}

	@Test
	@DisplayName("a non blank key becomes a bearer header")
	void bearerHeaderForRealKey() {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create("https://api.openai.com"),
				new SensitiveString("sk-live"), Duration.ofSeconds(3), Duration.ofSeconds(30));
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("Bearer sk-live", headers.get("Authorization"));
	}

	@Test
	@DisplayName("a non object stream_options field is replaced")
	void malformedStreamOptionsReplaced() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"stream_options\":\"bad\"}", null));
		assertTrue(result.path("stream_options").isObject());
		assertEquals(true, result.path("stream_options").path("include_usage").asBoolean());
	}
}