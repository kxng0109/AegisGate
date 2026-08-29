package io.github.kxng0109.aegisgate.proxy.protocol;

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
 * Unit tests for {@link OllamaAdapter}: message mapping, options translation, the completion bound as num_predict, and
 * the keyless header set.
 */
@DisplayName("OllamaAdapter")
class OllamaAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OllamaAdapter adapter = new OllamaAdapter(objectMapper);

	@Test
	@DisplayName("maps messages and options into the native body")
	void mapsMessagesAndOptions() {
		String body = """
				{"model":"llama3.2","messages":[
				  {"role":"system","content":"Be brief"},
				  {"role":"user","content":"Why is the sky blue?"}
				],"temperature":0.4,"top_p":0.9,"stop":["END"],"max_tokens":200}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));

		assertEquals("llama3.2", result.get("model").asString());
		assertTrue(result.get("stream").asBoolean());
		JsonNode messages = result.get("messages");
		assertEquals(2, messages.size());
		assertEquals("system", messages.get(0).get("role").asString());
		assertEquals("Why is the sky blue?", messages.get(1).get("content").asString());
		JsonNode options = result.get("options");
		assertEquals(0.4, options.get("temperature").asDouble());
		assertEquals(0.9, options.get("top_p").asDouble());
		assertEquals("END", options.get("stop").get(0).asString());
		assertEquals(200, options.get("num_predict").asInt());
	}

	@Test
	@DisplayName("applies the model override")
	void appliesModelOverride() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[]}", "llama3.1:8b"));
		assertEquals("llama3.1:8b", result.get("model").asString());
	}

	@Test
	@DisplayName("joins text content parts into a single string")
	void joinsContentParts() {
		String body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
				+ "{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}},"
				+ "{\"type\":\"text\",\"text\":\"two\"}]}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("one\ntwo", result.get("messages").get(0).get("content").asString());
	}

	@Test
	@DisplayName("messages with missing roles or content are skipped")
	void skipsEmptyMessages() {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"   \",\"content\":\"x\"},"
				+ "{\"role\":\"user\"},"
				+ "{\"content\":\"x\"},"
				+ "{\"role\":\"user\",\"content\":{\"x\":1}}"
				+ "]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(0, result.get("messages").size());
	}

	@Test
	@DisplayName("no options are emitted when the client sent none")
	void noOptionsWhenAbsent() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
						null
				));
		JsonNode options = result.get("options");
		assertEquals(0, options.size());
	}

	@Test
	@DisplayName("a single string stop becomes a one element options array")
	void singleStringStop() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":\"END\"}", null));
		assertEquals(1, result.path("options").get("stop").size());
	}

	@Test
	@DisplayName("a malformed stop value is dropped")
	void malformedStopDropped() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":42}", null));
		assertFalse(result.path("options").has("stop"));
	}

	@Test
	@DisplayName("a stop array with non text entries keeps only the text ones")
	void stopArrayFiltersNonText() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":[\"END\",42]}", null));
		assertEquals(1, result.path("options").get("stop").size());
		assertEquals("END", result.path("options").get("stop").get(0).asString());
	}

	@Test
	@DisplayName("a message with null content is skipped")
	void nullContentSkipped() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":null}]}",
						null
				));
		assertEquals(0, result.get("messages").size());
	}

	@Test
	@DisplayName("a body without messages translates cleanly")
	void noMessages() {
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody("{\"model\":\"m\"}", null));
		assertEquals(0, result.get("messages").size());
		assertEquals(0, result.get("options").size());
	}

	@Test
	@DisplayName("builds the chat URL and sends no credentials")
	void buildsUrlAndHeaders() {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.OLLAMA, URI.create("http://localhost:11434"), null,
				Duration.ofSeconds(3), Duration.ofSeconds(120)
		);
		assertEquals("http://localhost:11434/api/chat", adapter.buildUpstreamUrl(config).toString());
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("application/json", headers.get("Content-Type"));
		assertFalse(headers.containsKey("Authorization"), "local Ollama needs no credentials");
		assertFalse(headers.containsKey("x-api-key"));
	}
}