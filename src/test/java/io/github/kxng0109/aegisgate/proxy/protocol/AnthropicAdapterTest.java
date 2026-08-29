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
 * Unit tests for {@link AnthropicAdapter}: system message extraction, role and content block mapping, parameter
 * translation, max tokens defaulting, and dropping of parameters Anthropic has no equivalent for.
 */
@DisplayName("AnthropicAdapter")
class AnthropicAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AnthropicAdapter adapter = new AnthropicAdapter(objectMapper);

	@Test
	@DisplayName("translates system, user, and assistant messages")
	void translatesMessages() throws Exception {
		String body = """
				{"model":"claude-sonnet-5","messages":[
				  {"role":"system","content":"You are helpful"},
				  {"role":"user","content":"Hello"},
				  {"role":"assistant","content":[{"type":"text","text":"Hi"}]}
				]}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));

		assertEquals("claude-sonnet-5", result.get("model").asText());
		assertEquals("You are helpful", result.get("system").asText());
		JsonNode messages = result.get("messages");
		assertEquals(2, messages.size());
		assertEquals("user", messages.get(0).get("role").asText());
		assertEquals("text", messages.get(0).path("content").get(0).get("type").asText());
		assertEquals("Hello", messages.get(0).path("content").get(0).get("text").asText());
		assertEquals("assistant", messages.get(1).get("role").asText());
		assertTrue(result.get("stream").asBoolean());
	}

	@Test
	@DisplayName("defaults max tokens when the client sent none")
	void defaultsMaxTokens() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
						null
				));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("uses the client token bound, preferring max_completion_tokens")
	void prefersMaxCompletionTokens() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[],\"max_tokens\":100,\"max_completion_tokens\":250}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(250, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("clamps temperature into the Anthropic range")
	void clampsTemperature() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[],\"temperature\":2.5}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(AnthropicAdapter.MAX_TEMPERATURE, result.get("temperature").asDouble());
	}

	@Test
	@DisplayName("translates stop and top_p")
	void translatesStopAndTopP() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[],\"top_p\":0.9,\"stop\":[\"END\",\"STOP\"]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(0.9, result.get("top_p").asDouble());
		assertEquals(2, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asText());
	}

	@Test
	@DisplayName("drops parameters Anthropic has no equivalent for")
	void dropsUnsupportedParameters() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[],\"frequency_penalty\":1.5,\"presence_penalty\":1.0,"
				+ "\"logit_bias\":{\"1\":2},\"n\":3,\"seed\":42,\"user\":\"u\"}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertFalse(result.has("frequency_penalty"));
		assertFalse(result.has("presence_penalty"));
		assertFalse(result.has("logit_bias"));
		assertFalse(result.has("n"));
		assertFalse(result.has("seed"));
		assertFalse(result.has("user"));
	}

	@Test
	@DisplayName("skips unsupported roles and content parts")
	void skipsUnsupportedContent() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"tool\",\"content\":\"result\"},"
				+ "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}"
				+ "]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(1, result.get("messages").size());
		assertEquals("user", result.get("messages").get(0).get("role").asText());
		assertEquals(
				0, result.get("messages").get(0).get("content").size(),
				"image parts have no Anthropic text equivalent and are dropped"
		);
	}

	@Test
	@DisplayName("applies the model override")
	void appliesModelOverride() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[]}", "claude-opus-5"));
		assertEquals("claude-opus-5", result.get("model").asText());
	}

	@Test
	@DisplayName("clamps temperature below zero up to the Anthropic range")
	void clampsLowTemperature() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[],\"temperature\":-5}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(0.0, result.get("temperature").asDouble());
	}

	@Test
	@DisplayName("a zero client token bound falls back to the default")
	void zeroTokenBoundFallsBack() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"max_tokens\":0}", null));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("a single string stop sequence becomes a one element array")
	void singleStringStopBecomesArray() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":\"END\"}", null));
		assertEquals(1, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asText());
	}

	@Test
	@DisplayName("a malformed stop value is dropped")
	void malformedStopDropped() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":42}", null));
		assertFalse(result.has("stop_sequences"));
	}

	@Test
	@DisplayName("no system parameter when there is no system message")
	void noSystemWhenAbsent() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
						null
				));
		assertFalse(result.has("system"));
	}

	@Test
	@DisplayName("blank system content produces no system parameter")
	void blankSystemDropped() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"   \"}]}",
						null
				));
		assertFalse(result.has("system"));
	}

	@Test
	@DisplayName("a message with a null role is dropped")
	void nullRoleDropped() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[{\"content\":\"x\"}]}", null));
		assertEquals(0, result.get("messages").size());
	}

	@Test
	@DisplayName("a message with a non string non array content produces no blocks")
	void oddContentProducesNoBlocks() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":{\"x\":1}}]}",
						null
				));
		assertEquals(0, result.get("messages").get(0).get("content").size());
	}

	@Test
	@DisplayName("concatenates multiple system messages")
	void concatenatesSystemMessages() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\",\"content\":\"first\"},"
				+ "{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"second\"}]},"
				+ "{\"role\":\"user\",\"content\":\"x\"}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("first\nsecond", result.get("system").asText());
	}

	@Test
	@DisplayName("content arrays with only non text parts produce no text")
	void contentArrayWithoutText() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}},{\"type\":\"refusal\",\"refusal\":\"r\"}]},"
				+ "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertFalse(result.has("system"), "no text part means the system is empty");
		assertEquals(1, result.get("messages").size());
		assertEquals(0, result.get("messages").get(0).get("content").size());
	}

	@Test
	@DisplayName("a user content array mixes text and non text parts")
	void mixedContentParts() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
				+ "{\"type\":\"text\",\"text\":\"keep\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(1, result.get("messages").get(0).get("content").size());
		assertEquals("keep", result.get("messages").get(0).path("content").get(0).path("text").asText());
	}

	@Test
	@DisplayName("a body without messages translates cleanly")
	void noMessages() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\"}", null));
		assertEquals(0, result.get("messages").size());
		assertFalse(result.has("system"));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("a message with null content produces no blocks")
	void nullContentProducesNoBlocks() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}]}", null));
		assertEquals(0, result.get("messages").get(0).get("content").size());
	}

	@Test
	@DisplayName("a system message with empty text parts is dropped")
	void systemWithEmptyPartsDropped() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]},"
				+ "{\"role\":\"user\",\"content\":\"x\"}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertFalse(result.has("system"), "no text parts means no system parameter");
		assertEquals(1, result.get("messages").size());
	}

	@Test
	@DisplayName("a stop array with non text entries keeps only the text ones")
	void stopArrayFiltersNonText() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[],\"stop\":[\"END\",42,\"STOP\",false]}",
						null
				));
		assertEquals(2, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asText());
		assertEquals("STOP", result.get("stop_sequences").get(1).asText());
	}

	@Test
	@DisplayName("an object content system message produces no system parameter")
	void objectContentSystemDropped() throws Exception {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":["
								+ "{\"role\":\"system\",\"content\":{\"type\":\"text\",\"text\":\"x\"}},"
								+ "{\"role\":\"user\",\"content\":\"hi\"}]}", null
				));
		assertFalse(result.has("system"));
		assertEquals(1, result.get("messages").size());
	}

	@Test
	@DisplayName("missing or null system content produces no system parameter")
	void nullSystemContentDropped() throws Exception {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\"},"
				+ "{\"role\":\"system\",\"content\":null},"
				+ "{\"role\":\"user\",\"content\":\"hi\"}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertFalse(result.has("system"));
		assertEquals(1, result.get("messages").size());
	}

	@Test
	@DisplayName("headers tolerate a null provider key")
	void headersTolerateNullKey() {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.ANTHROPIC, URI.create("https://api.anthropic.com"), null,
				Duration.ofSeconds(3), Duration.ofSeconds(30)
		);
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("", headers.get("x-api-key"));
	}

	@Test
	@DisplayName("builds the messages URL and Anthropic headers")
	void buildsUrlAndHeaders() {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.ANTHROPIC,
				URI.create("https://api.anthropic.com"),
				new SensitiveString("sk-ant"),
				Duration.ofSeconds(3), Duration.ofSeconds(30)
		);
		assertEquals("https://api.anthropic.com/v1/messages", adapter.buildUpstreamUrl(config).toString());
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("sk-ant", headers.get("x-api-key"));
		assertEquals(AnthropicAdapter.ANTHROPIC_VERSION, headers.get("anthropic-version"));
		assertEquals("application/json", headers.get("Content-Type"));
	}
}