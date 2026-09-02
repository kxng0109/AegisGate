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

@DisplayName("DeepSeekAdapter")
@SuppressWarnings("DataFlowIssue")
class DeepSeekAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final DeepSeekAdapter adapter = new DeepSeekAdapter(objectMapper);

	@Test
	@DisplayName("builds upstream URL and sets Bearer Authorization header")
	void buildsUrlAndHeaders() {
		ProviderConfig config = new ProviderConfig(
				"deepseek-prod",
				ProviderType.DEEPSEEK,
				URI.create("https://api.deepseek.com"),
				new SensitiveString("sk-deepseek-test"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);

		assertEquals("https://api.deepseek.com/chat/completions", adapter.buildUpstreamUrl(config).toString());
		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("application/json", headers.get("Content-Type"));
		assertEquals("Bearer sk-deepseek-test", headers.get("Authorization"));
	}

	@Test
	@DisplayName("translates request with thinking mode, reasoning effort, and tool calls")
	void translatesRequest() throws Exception {
		String body = """
				{
				  "model": "deepseek-reasoner",
				  "messages": [
				    {"role": "system", "content": "You are a quantitative researcher."},
				    {"role": "user", "content": "Compute derivative of sin(x)"},
				    {
				      "role": "assistant",
				      "content": "Result is cos(x)",
				      "reasoning_content": "The derivative of sin(x) with respect to x is cos(x).",
				      "tool_calls": [
				        {"id": "call_123", "type": "function", "function": {"name": "verify_derivative", "arguments": "{\\"f\\":\\"sin(x)\\"}"}}
				      ]
				    },
				    {"role": "tool", "tool_call_id": "call_123", "name": "verify_derivative", "content": "{\\"correct\\":true}"}
				  ],
				  "temperature": 0.0,
				  "max_tokens": 4096,
				  "reasoning_effort": "high",
				  "tools": [
				    {
				      "type": "function",
				      "function": {
				        "name": "verify_derivative",
				        "description": "Verifies math derivative",
				        "parameters": {"type": "object", "properties": {"f": {"type": "string"}}}
				      }
				    }
				  ]
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);

		assertEquals("deepseek-reasoner", result.path("model").asString());
		assertTrue(result.path("stream").asBoolean());
		assertEquals("high", result.path("reasoning_effort").asString());
		assertEquals("enabled", result.path("thinking").path("type").asString());

		JsonNode messages = result.path("messages");
		assertEquals(4, messages.size());
		assertEquals(
				"The derivative of sin(x) with respect to x is cos(x).",
				messages.get(2).path("reasoning_content").asString()
		);
		assertEquals("call_123", messages.get(2).path("tool_calls").get(0).path("id").asString());
		assertEquals("call_123", messages.get(3).path("tool_call_id").asString());

		assertTrue(result.has("tools"));
		assertEquals(1, result.path("tools").size());
	}

	@Test
	@DisplayName("tests edge branches for URL formatting, headers, overrides, and options")
	void testsEdgeBranches() throws Exception {
		// Base URL already containing /chat/completions
		ProviderConfig directConfig = new ProviderConfig(
				"deepseek-direct",
				ProviderType.DEEPSEEK,
				URI.create("https://api.deepseek.com/chat/completions/"),
				null,
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		assertEquals("https://api.deepseek.com/chat/completions", adapter.buildUpstreamUrl(directConfig).toString());
		assertEquals("Bearer ", adapter.buildRequestHeaders(directConfig).get("Authorization"));

		// Model override and options
		String body = """
				{
				  "model": "deepseek-chat",
				  "messages": [],
				  "top_p": 0.8,
				  "stop": ["END"],
				  "tool_choice": "auto",
				  "response_format": {"type": "json_object"},
				  "thinking": {"type": "disabled"},
				  "stream_options": {"include_usage": true}
				}""";

		String translated = adapter.buildRequestBody(body, "deepseek-v4-pro");
		JsonNode result = objectMapper.readTree(translated);

		assertEquals("deepseek-v4-pro", result.path("model").asString());
		assertEquals(0.8, result.path("top_p").asDouble());
		assertEquals("END", result.path("stop").get(0).asString());
		assertEquals("auto", result.path("tool_choice").asString());
		assertEquals("json_object", result.path("response_format").path("type").asString());
		assertEquals("disabled", result.path("thinking").path("type").asString());
		assertTrue(result.path("stream_options").path("include_usage").asBoolean());

		SseNormalizer normalizer = adapter.newNormalizer(true, "deepseek-chat");
		assertNotNull(normalizer);
		assertTrue(normalizer instanceof DeepSeekSseNormalizer);

		// Fallback thinking mode for r1 model and max_completion_tokens
		String r1Body = """
				{
				  "model": "deepseek-r1",
				  "messages": [
				    {"name": "Alice"}
				  ],
				  "max_completion_tokens": 2048,
				  "reasoning_effort": "   "
				}""";
		String r1Translated = adapter.buildRequestBody(r1Body, null);
		JsonNode r1Node = objectMapper.readTree(r1Translated);
		assertEquals("deepseek-r1", r1Node.path("model").asString());
		assertEquals("enabled", r1Node.path("thinking").path("type").asString());
		assertEquals(2048, r1Node.path("max_tokens").asInt());

		// Edge case: null messages, empty tools array, null tool_choice, non-object response_format, non-object stream_options, non-reasoner model
		String minimalBody = """
				{
				  "model": "deepseek-chat",
				  "tools": [],
				  "tool_choice": null,
				  "response_format": "not-object",
				  "stream_options": 123,
				  "stop": null
				}""";
		String minTranslated = adapter.buildRequestBody(minimalBody, null);
		JsonNode minNode = objectMapper.readTree(minTranslated);
		assertEquals("deepseek-chat", minNode.path("model").asString());
		assertFalse(minNode.has("thinking"));
		assertFalse(minNode.has("tools"));
		assertFalse(minNode.has("tool_choice"));
		assertFalse(minNode.has("response_format"));
		assertFalse(minNode.has("stream_options"));
		assertFalse(minNode.has("stop"));

		// Malformed JSON throws
		String invalidJson = "{ malformed json ";
		assertThrows(Exception.class, () -> adapter.buildRequestBody(invalidJson, null));
	}
}
