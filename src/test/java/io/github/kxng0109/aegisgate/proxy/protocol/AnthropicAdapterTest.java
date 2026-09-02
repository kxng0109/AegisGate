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
@SuppressWarnings("DataFlowIssue")
class AnthropicAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AnthropicAdapter adapter = new AnthropicAdapter(objectMapper);

	@Test
	@DisplayName("translates system, user, and assistant messages")
	void translatesMessages() {
		String body = """
				{"model":"claude-sonnet-5","messages":[
				  {"role":"system","content":"You are helpful"},
				  {"role":"user","content":"Hello"},
				  {"role":"assistant","content":[{"type":"text","text":"Hi"}]}
				]}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));

		assertEquals("claude-sonnet-5", result.get("model").asString());
		assertEquals("You are helpful", result.get("system").asString());
		JsonNode messages = result.get("messages");
		assertEquals(2, messages.size());
		assertEquals("user", messages.get(0).get("role").asString());
		assertEquals("text", messages.get(0).path("content").get(0).get("type").asString());
		assertEquals("Hello", messages.get(0).path("content").get(0).get("text").asString());
		assertEquals("assistant", messages.get(1).get("role").asString());
		assertTrue(result.get("stream").asBoolean());
	}

	@Test
	@DisplayName("defaults max tokens when the client sent none")
	void defaultsMaxTokens() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
						null
				));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("uses the client token bound, preferring max_completion_tokens")
	void prefersMaxCompletionTokens() {
		String body = "{\"model\":\"m\",\"messages\":[],\"max_tokens\":100,\"max_completion_tokens\":250}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(250, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("clamps temperature into the Anthropic range")
	void clampsTemperature() {
		String body = "{\"model\":\"m\",\"messages\":[],\"temperature\":2.5}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(AnthropicAdapter.MAX_TEMPERATURE, result.get("temperature").asDouble());
	}

	@Test
	@DisplayName("translates stop and top_p")
	void translatesStopAndTopP() {
		String body = "{\"model\":\"m\",\"messages\":[],\"top_p\":0.9,\"stop\":[\"END\",\"STOP\"]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(0.9, result.get("top_p").asDouble());
		assertEquals(2, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asString());
	}

	@Test
	@DisplayName("drops parameters Anthropic has no equivalent for")
	void dropsUnsupportedParameters() {
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
	void skipsUnsupportedContent() {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"unsupported_custom_role\",\"content\":\"result\"},"
				+ "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}"
				+ "]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(1, result.get("messages").size());
		assertEquals("user", result.get("messages").get(0).get("role").asString());
		assertEquals(
				0, result.get("messages").get(0).get("content").size(),
				"image parts have no Anthropic text equivalent and are dropped"
		);
	}

	@Test
	@DisplayName("applies the model override")
	void appliesModelOverride() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[]}", "claude-opus-5"));
		assertEquals("claude-opus-5", result.get("model").asString());
	}

	@Test
	@DisplayName("clamps temperature below zero up to the Anthropic range")
	void clampsLowTemperature() {
		String body = "{\"model\":\"m\",\"messages\":[],\"temperature\":-5}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(0.0, result.get("temperature").asDouble());
	}

	@Test
	@DisplayName("a zero client token bound falls back to the default")
	void zeroTokenBoundFallsBack() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"max_tokens\":0}", null));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("a single string stop sequence becomes a one element array")
	void singleStringStopBecomesArray() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":\"END\"}", null));
		assertEquals(1, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asString());
	}

	@Test
	@DisplayName("a malformed stop value is dropped")
	void malformedStopDropped() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[],\"stop\":42}", null));
		assertFalse(result.has("stop_sequences"));
	}

	@Test
	@DisplayName("no system parameter when there is no system message")
	void noSystemWhenAbsent() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
						null
				));
		assertFalse(result.has("system"));
	}

	@Test
	@DisplayName("blank system content produces no system parameter")
	void blankSystemDropped() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"   \"}]}",
						null
				));
		assertFalse(result.has("system"));
	}

	@Test
	@DisplayName("a message with a null role is dropped")
	void nullRoleDropped() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[{\"content\":\"x\"}]}", null));
		assertEquals(0, result.get("messages").size());
	}

	@Test
	@DisplayName("a message with a non string non array content produces no blocks")
	void oddContentProducesNoBlocks() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":{\"x\":1}}]}",
						null
				));
		assertEquals(0, result.get("messages").get(0).get("content").size());
	}

	@Test
	@DisplayName("concatenates multiple system messages")
	void concatenatesSystemMessages() {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\",\"content\":\"first\"},"
				+ "{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"second\"}]},"
				+ "{\"role\":\"user\",\"content\":\"x\"}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("first\nsecond", result.get("system").asString());
	}

	@Test
	@DisplayName("content arrays with only non text parts produce no text")
	void contentArrayWithoutText() {
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
	void mixedContentParts() {
		String body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
				+ "{\"type\":\"text\",\"text\":\"keep\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals(1, result.get("messages").get(0).get("content").size());
		assertEquals("keep", result.get("messages").get(0).path("content").get(0).path("text").asString());
	}

	@Test
	@DisplayName("a body without messages translates cleanly")
	void noMessages() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\"}", null));
		assertEquals(0, result.get("messages").size());
		assertFalse(result.has("system"));
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, result.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("a message with null content produces no blocks")
	void nullContentProducesNoBlocks() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody("{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}]}", null));
		assertEquals(0, result.get("messages").get(0).get("content").size());
	}

	@Test
	@DisplayName("a system message with empty text parts is dropped")
	void systemWithEmptyPartsDropped() {
		String body = "{\"model\":\"m\",\"messages\":["
				+ "{\"role\":\"system\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]},"
				+ "{\"role\":\"user\",\"content\":\"x\"}]}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertFalse(result.has("system"), "no text parts means no system parameter");
		assertEquals(1, result.get("messages").size());
	}

	@Test
	@DisplayName("a stop array with non text entries keeps only the text ones")
	void stopArrayFiltersNonText() {
		JsonNode result = objectMapper.readTree(
				adapter.buildRequestBody(
						"{\"model\":\"m\",\"messages\":[],\"stop\":[\"END\",42,\"STOP\",false]}",
						null
				));
		assertEquals(2, result.get("stop_sequences").size());
		assertEquals("END", result.get("stop_sequences").get(0).asString());
		assertEquals("STOP", result.get("stop_sequences").get(1).asString());
	}

	@Test
	@DisplayName("an object content system message produces no system parameter")
	void objectContentSystemDropped() {
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
	void nullSystemContentDropped() {
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
				URI.create("https://api.anthropic.com/"),
				new SensitiveString("sk-ant"),
				Duration.ofSeconds(3), Duration.ofSeconds(30)
		);
		assertEquals("https://api.anthropic.com/v1/messages", adapter.buildUpstreamUrl(config).toString());
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("sk-ant", headers.get("x-api-key"));
		assertEquals(AnthropicAdapter.ANTHROPIC_VERSION, headers.get("anthropic-version"));
		assertEquals("application/json", headers.get("Content-Type"));
	}

	@Test
	@DisplayName("translates array system message and ignores non-text stop sequences")
	void translatesArraySystemAndNonStringStop() {
		String body = """
				{"model":"claude-sonnet-5",
				 "stop": 123,
				 "messages":[
				  {"role":"system","content":[{"type":"text","text":"Part 1"},{"type":"image","url":"..."},{"type":"text","text":"Part 2"}]},
				  {"role":"user","content":[{"type":"image","url":"..."},{"type":"text","text":"Hello"}]}
				]}""";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("Part 1\nPart 2", result.get("system").asString());
		assertFalse(result.has("stop_sequences"));
		JsonNode userContent = result.get("messages").get(0).get("content");
		assertEquals(1, userContent.size());
		assertEquals("Hello", userContent.get(0).get("text").asString());
	}

	@Test
	@DisplayName("tolerates non-array non-string stop sequences in stop array")
	void toleratesNonStringArrayStop() {
		String body = """
				{"model":"claude-sonnet-5",
				 "stop": ["STOP", 123, null],
				 "messages":[{"role":"user","content":"hi"}]
				}""";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		JsonNode stop = result.get("stop_sequences");
		assertEquals(1, stop.size());
		assertEquals("STOP", stop.get(0).asString());
	}

	@Test
	@DisplayName("tolerates null messages array in request body")
	void toleratesNullMessages() {
		String body = "{\"model\":\"claude-sonnet-5\"}";
		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("claude-sonnet-5", result.get("model").asString());
		assertEquals(0, result.get("messages").size());
	}

	@Test
	@DisplayName("translates tools, tool_choice, and multi-turn tool execution loop")
	void translatesToolsAndToolExecutionLoop() {
		String body = """
				{
				  "model": "claude-3-7-sonnet-20250219",
				  "messages": [
				    {"role": "user", "content": "What is the weather in Paris?"},
				    {
				      "role": "assistant",
				      "content": "Checking weather now...",
				      "tool_calls": [
				        {
				          "id": "toolu_01A",
				          "type": "function",
				          "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}
				        }
				      ]
				    },
				    {
				      "role": "tool",
				      "tool_call_id": "toolu_01A",
				      "name": "get_weather",
				      "content": "{\\"temp\\": 21}"
				    }
				  ],
				  "tools": [
				    {
				      "type": "function",
				      "function": {
				        "name": "get_weather",
				        "description": "Fetch weather",
				        "parameters": {
				          "type": "object",
				          "properties": {"city": {"type": "string"}},
				          "required": ["city"]
				        }
				      }
				    }
				  ],
				  "tool_choice": "auto",
				  "parallel_tool_calls": false
				}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertTrue(result.has("tools"));
		assertEquals(1, result.get("tools").size());
		assertEquals("get_weather", result.get("tools").get(0).get("name").asString());
		assertEquals("object", result.get("tools").get(0).get("input_schema").get("type").asString());

		assertTrue(result.has("tool_choice"));
		assertEquals("auto", result.get("tool_choice").get("type").asString());
		assertTrue(result.get("tool_choice").get("disable_parallel_tool_use").asBoolean());

		JsonNode messages = result.get("messages");
		assertEquals(3, messages.size());

		// Turn 1: user
		assertEquals("user", messages.get(0).get("role").asString());

		// Turn 2: assistant with tool_use
		assertEquals("assistant", messages.get(1).get("role").asString());
		JsonNode asstContent = messages.get(1).get("content");
		assertEquals(2, asstContent.size()); // text block + tool_use block
		assertEquals("text", asstContent.get(0).get("type").asString());
		assertEquals("tool_use", asstContent.get(1).get("type").asString());
		assertEquals("toolu_01A", asstContent.get(1).get("id").asString());
		assertEquals("get_weather", asstContent.get(1).get("name").asString());
		assertEquals("Paris", asstContent.get(1).path("input").path("city").asString());

		// Turn 3: tool result converted to user turn with tool_result block
		assertEquals("user", messages.get(2).get("role").asString());
		JsonNode toolResContent = messages.get(2).get("content");
		assertEquals(1, toolResContent.size());
		assertEquals("tool_result", toolResContent.get(0).get("type").asString());
		assertEquals("toolu_01A", toolResContent.get(0).get("tool_use_id").asString());
		assertTrue(toolResContent.get(0).get("content").asString().contains("21"));
	}

	@Test
	@DisplayName("tests edge branches for tool calls with invalid args, null tool ID, and normalizer")
	void testsEdgeBranches() throws Exception {
		String body = """
				{
				  "model": "claude-3-7-sonnet",
				  "messages": [
				    {
				      "role": "assistant",
				      "tool_calls": [
				        {"id": "c1", "type": "function", "function": {"name": "calc", "arguments": "invalid-json"}},
				        {"id": "c2", "type": "function", "function": {"name": ""}}
				      ]
				    },
				    {
				      "role": "tool"
				    }
				  ],
				  "tools": [],
				  "tool_choice": null
				}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		JsonNode messages = result.get("messages");
		assertEquals(2, messages.size());

		// Turn 1: assistant with invalid args fallback
		JsonNode asstBlocks = messages.get(0).get("content");
		assertEquals(1, asstBlocks.size());
		assertEquals("calc", asstBlocks.get(0).get("name").asString());
		assertTrue(asstBlocks.get(0).get("input").isObject());

		// Turn 2: tool with default empty strings
		JsonNode toolBlocks = messages.get(1).get("content");
		assertEquals(1, toolBlocks.size());
		assertEquals("", toolBlocks.get(0).get("tool_use_id").asString());
		assertEquals("", toolBlocks.get(0).get("content").asString());

		assertFalse(result.has("tools"));
		assertFalse(result.has("tool_choice"));

		SseNormalizer normalizer = adapter.newNormalizer(true, "claude-3-7-sonnet");
		assertNotNull(normalizer);
		assertTrue(normalizer instanceof AnthropicSseNormalizer);
	}

	@Test
	@DisplayName("tests developer messages, multiple system messages, and mixed stop sequences")
	void testsDeveloperAndMultipleSystemMessages() {
		String body = """
				{
				  "model": "claude-3-7-sonnet",
				  "messages": [
				    {"role": "developer", "content": "Instruction 1"},
				    {"role": "system", "content": [{"type": "text", "text": "Instruction 2"}]},
				    {"role": "developer", "content": "   "},
				    {"role": "system", "content": null},
				    {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "x"}}]}
				  ],
				  "stop": ["STOP1", 123, "STOP2"]
				}""";

		JsonNode result = objectMapper.readTree(adapter.buildRequestBody(body, null));
		assertEquals("Instruction 1\nInstruction 2", result.get("system").asString());
		assertEquals(2, result.get("stop_sequences").size());
		assertEquals("STOP1", result.get("stop_sequences").get(0).asString());
		assertEquals("STOP2", result.get("stop_sequences").get(1).asString());
	}
}