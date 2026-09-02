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

@DisplayName("GeminiAdapter")
@SuppressWarnings("DataFlowIssue")
class GeminiAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final GeminiAdapter adapter = new GeminiAdapter(objectMapper);

	@Test
	@DisplayName("builds Developer API URL and sets x-goog-api-key header")
	void buildsDeveloperApiUrlAndHeaders() {
		ProviderConfig config = new ProviderConfig(
				"gemini-dev",
				ProviderType.GEMINI,
				URI.create("https://generativelanguage.googleapis.com"),
				new SensitiveString("gemini-test-key"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);

		URI uri = adapter.buildUpstreamUrl(config);
		assertTrue(uri.toString().contains(":streamGenerateContent?alt=sse"));

		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("application/json", headers.get("Content-Type"));
		assertEquals("gemini-test-key", headers.get("x-goog-api-key"));
		assertFalse(headers.containsKey("Authorization"));
	}

	@Test
	@DisplayName("builds Vertex AI URL and sets Authorization Bearer header")
	void buildsVertexAiUrlAndHeaders() {
		ProviderConfig config = new ProviderConfig(
				"vertex-prod",
				ProviderType.VERTEX_AI,
				URI.create(
						"https://us-central1-aiplatform.googleapis.com/v1/projects/my-proj/locations/us-central1/publishers/google/models/gemini-2.5-pro"),
				new SensitiveString("oauth-access-token"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);

		URI uri = adapter.buildUpstreamUrl(config);
		assertTrue(uri.toString().endsWith(":streamGenerateContent?alt=sse"));

		Map<String, String> headers = adapter.buildRequestHeaders(config);
		assertEquals("application/json", headers.get("Content-Type"));
		assertEquals("Bearer oauth-access-token", headers.get("Authorization"));
		assertFalse(headers.containsKey("x-goog-api-key"));
	}

	@Test
	@DisplayName("translates system, user, assistant, and tool messages to contents schema")
	void translatesMessages() throws Exception {
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {"role": "system", "content": "You are a quantitative researcher."},
				    {"role": "user", "content": "What is the capital of France?"},
				    {"role": "assistant", "content": "Paris", "tool_calls": [
				      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
				    ]},
				    {"role": "tool", "name": "get_weather", "content": "{\\"temp\\": 22}"}
				  ],
				  "temperature": 0.5,
				  "top_p": 0.9,
				  "max_tokens": 1000,
				  "stop": ["###", "END"],
				  "reasoning_effort": "high"
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);

		assertTrue(result.has("systemInstruction"));
		assertEquals("system", result.path("systemInstruction").path("role").asString());
		assertEquals(
				"You are a quantitative researcher.",
				result.path("systemInstruction").path("parts").get(0).path("text").asString()
		);

		JsonNode contents = result.path("contents");
		assertEquals(3, contents.size());

		// Turn 1: user
		assertEquals("user", contents.get(0).path("role").asString());
		assertEquals("What is the capital of France?", contents.get(0).path("parts").get(0).path("text").asString());

		// Turn 2: model with tool call
		assertEquals("model", contents.get(1).path("role").asString());
		assertEquals("Paris", contents.get(1).path("parts").get(0).path("text").asString());
		assertEquals("get_weather", contents.get(1).path("parts").get(1).path("functionCall").path("name").asString());
		assertEquals(
				"Paris",
				contents.get(1).path("parts").get(1).path("functionCall").path("args").path("city").asString()
		);

		// Turn 3: user with functionResponse
		assertEquals("user", contents.get(2).path("role").asString());
		assertEquals(
				"get_weather",
				contents.get(2).path("parts").get(0).path("functionResponse").path("name").asString()
		);
		assertEquals(
				22,
				contents.get(2).path("parts").get(0).path("functionResponse").path("response").path("temp").asInt()
		);

		// Generation config
		JsonNode genConfig = result.path("generationConfig");
		assertEquals(0.5, genConfig.path("temperature").asDouble());
		assertEquals(0.9, genConfig.path("topP").asDouble());
		assertEquals(1000, genConfig.path("maxOutputTokens").asInt());
		assertEquals(2, genConfig.path("stopSequences").size());
		assertTrue(genConfig.path("thinkingConfig").path("includeThoughts").asBoolean());
	}

	@Test
	@DisplayName("translates tools and tool_choice to Gemini schema and toolConfig")
	void translatesToolsAndToolChoice() throws Exception {
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [{"role": "user", "content": "Calculate stats"}],
				  "tools": [
				    {
				      "type": "function",
				      "function": {
				        "name": "calc",
				        "description": "Calculate math",
				        "parameters": {
				          "type": "object",
				          "properties": {"expr": {"type": "string"}},
				          "required": ["expr"]
				        }
				      }
				    }
				  ],
				  "tool_choice": "required",
				  "response_format": {"type": "json_object"}
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);

		assertTrue(result.has("tools"));
		JsonNode fn = result.path("tools").get(0).path("functionDeclarations").get(0);
		assertEquals("calc", fn.path("name").asString());
		assertEquals("OBJECT", fn.path("parameters").path("type").asString());
		assertEquals("STRING", fn.path("parameters").path("properties").path("expr").path("type").asString());

		assertTrue(result.has("toolConfig"));
		assertEquals("ANY", result.path("toolConfig").path("functionCallingConfig").path("mode").asString());

		assertEquals("application/json", result.path("generationConfig").path("responseMimeType").asString());
	}

	@Test
	@DisplayName("translates user image parts in inlineData format")
	void translatesUserImageParts() throws Exception {
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {
				      "role": "user",
				      "content": [
				        {"type": "text", "text": "Describe this image:"},
				        {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="}}
				      ]
				    }
				  ]
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);

		JsonNode parts = result.path("contents").get(0).path("parts");
		assertEquals(2, parts.size());
		assertEquals("Describe this image:", parts.get(0).path("text").asString());
		assertEquals("image/png", parts.get(1).path("inlineData").path("mimeType").asString());
		assertEquals("iVBORw0KGgoAAAANSUhEUg==", parts.get(1).path("inlineData").path("data").asString());
	}

	@Test
	@DisplayName("tests edge branches for URLs, headers, and normalizer")
	void testsEdgeBranchesUrlsHeadersNormalizer() {
		// Base URL already containing generateContent
		ProviderConfig directConfig = new ProviderConfig(
				"gemini-direct",
				ProviderType.GEMINI,
				URI.create(
						"https://generativelanguage.googleapis.com/v1beta/models/custom:streamGenerateContent?alt=sse"),
				null,
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		assertEquals(
				"https://generativelanguage.googleapis.com/v1beta/models/custom:streamGenerateContent?alt=sse",
				adapter.buildUpstreamUrl(directConfig).toString()
		);

		// Base URL ending with /models
		ProviderConfig modelsConfig = new ProviderConfig(
				"gemini-models",
				ProviderType.GEMINI,
				URI.create("https://generativelanguage.googleapis.com/v1beta/models/"),
				null,
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		assertTrue(adapter.buildUpstreamUrl(modelsConfig).toString()
		                  .endsWith("/models/gemini-2.5-flash:streamGenerateContent?alt=sse"));

		// Null API key headers
		Map<String, String> devHeaders = adapter.buildRequestHeaders(directConfig);
		assertEquals("", devHeaders.get("x-goog-api-key"));

		ProviderConfig vertexNullKey = new ProviderConfig(
				"vertex-null",
				ProviderType.VERTEX_AI,
				URI.create("https://us-central1-aiplatform.googleapis.com"),
				null,
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		Map<String, String> vertexHeaders = adapter.buildRequestHeaders(vertexNullKey);
		assertEquals("Bearer ", vertexHeaders.get("Authorization"));

		// Normalizer instantiation
		SseNormalizer normalizer = adapter.newNormalizer(true, "gemini-2.5-flash");
		assertNotNull(normalizer);
		assertTrue(normalizer instanceof GeminiSseNormalizer);
	}

	@Test
	@DisplayName("tests edge branches for message roles, tool calls, and structured response formats")
	void testsEdgeBranchesMessagesAndFormats() throws Exception {
		// Multiple system and developer messages, assistant with malformed tool args, tool with null name/content
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {"role": "developer", "content": "Dev instruction."},
				    {"role": "system", "content": "System instruction."},
				    {"role": "assistant", "tool_calls": [
				      {"id": "c1", "type": "function", "function": {"name": "calc", "arguments": "invalid-json"}},
				      {"id": "c2", "type": "function", "function": {"name": " "}}
				    ]},
				    {"role": "tool"},
				    {"role": "user", "content": null},
				    {
				      "role": "user",
				      "content": [
				        {"type": "image_url", "image_url": {"url": "https://example.com/image.png"}},
				        {"type": "other"}
				      ]
				    }
				  ],
				  "stop": "STOP_TOKEN",
				  "response_format": {
				    "type": "json_schema",
				    "json_schema": {
				      "schema": {
				        "type": "object",
				        "properties": {"score": {"type": "number"}}
				      }
				    }
				  },
				  "thinking": {"type": "enabled"}
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);

		assertTrue(result.has("systemInstruction"));
		assertEquals(
				"Dev instruction.\nSystem instruction.",
				result.path("systemInstruction").path("parts").get(0).path("text").asString()
		);

		JsonNode contents = result.path("contents");
		// assistant turn with fallback empty args
		JsonNode assistantTurn = contents.get(0);
		assertEquals("model", assistantTurn.path("role").asString());
		assertEquals("calc", assistantTurn.path("parts").get(0).path("functionCall").path("name").asString());
		assertTrue(assistantTurn.path("parts").get(0).path("functionCall").path("args").isObject());

		// tool turn with default name
		JsonNode toolTurn = contents.get(1);
		assertEquals("user", toolTurn.path("role").asString());
		assertEquals("tool_response", toolTurn.path("parts").get(0).path("functionResponse").path("name").asString());

		// response format and stop string
		assertEquals("application/json", result.path("generationConfig").path("responseMimeType").asString());
		assertEquals("OBJECT", result.path("generationConfig").path("responseSchema").path("type").asString());
		assertEquals("STOP_TOKEN", result.path("generationConfig").path("stopSequences").get(0).asString());
		assertTrue(result.path("generationConfig").path("thinkingConfig").path("includeThoughts").asBoolean());
	}

	@Test
	@DisplayName("handles null and empty messages and stop sequences safely")
	void handlesNullAndEmptyMessages() throws Exception {
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [],
				  "stop": ["VALID", 123],
				  "response_format": {"type": "text"}
				}""";

		String translated = adapter.buildRequestBody(body, null);
		JsonNode result = objectMapper.readTree(translated);
		assertFalse(result.has("systemInstruction"));
		assertEquals(0, result.path("contents").size());
		assertEquals(1, result.path("generationConfig").path("stopSequences").size());
		assertFalse(result.path("generationConfig").has("responseMimeType"));
	}

	@Test
	@DisplayName("covers all remaining branches in GeminiAdapter message, content, format, and tool handling")
	void coversAllRemainingBranchesInGeminiAdapter() throws Exception {
		// 1. messages null, effectiveMaxTokens <= 0, stop non-string/non-array, response_format non-object
		String body1 = """
				{
				  "model": "gemini-2.5-flash",
				  "max_tokens": 0,
				  "stop": 42,
				  "response_format": "not_an_object",
				  "tools": 123,
				  "tool_choice": null
				}""";
		JsonNode res1 = objectMapper.readTree(adapter.buildRequestBody(body1, null));
		assertFalse(res1.has("systemInstruction"));
		assertEquals(0, res1.path("contents").size());
		assertFalse(res1.path("generationConfig").has("maxOutputTokens"));
		assertFalse(res1.path("generationConfig").has("stopSequences"));
		assertFalse(res1.path("generationConfig").has("responseMimeType"));

		// 2. system message with array content (mixed text and non-text), blank system text
		String body2 = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {
				      "role": "system",
				      "content": [
				        {"type": "text", "text": "First sys line"},
				        {"type": "other", "text": "ignored"},
				        {"type": "text", "text": "Second sys line"}
				      ]
				    },
				    {
				      "role": "developer",
				      "content": "   "
				    },
				    {
				      "role": null,
				      "content": "Null role defaults to user"
				    },
				    {
				      "role": "assistant",
				      "content": [
				        {"type": "text", "text": "Assistant array text"}
				      ],
				      "tool_calls": [
				        {"function": {"name": "", "arguments": "{}"}},
				        {"function": {"name": "valid_fn", "arguments": "{\\"k\\":\\"v\\"}"}}
				      ]
				    },
				    {
				      "role": "tool",
				      "name": null,
				      "content": null
				    },
				    {
				      "role": "user",
				      "content": [
				        {"type": "text", "text": ""},
				        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,123456"}},
				        {"type": "image_url", "image_url": {"url": "data:missing-base64"}},
				        {"type": "image_url", "image_url": {"url": "https://remote.com/pic.jpg"}}
				      ]
				    }
				  ],
				  "response_format": {
				    "type": "json_schema",
				    "json_schema": {
				      "schema": "not_an_object"
				    }
				  },
				  "tools": [
				    {"type": "invalid"}
				  ],
				  "tool_choice": "none"
				}""";

		JsonNode res2 = objectMapper.readTree(adapter.buildRequestBody(body2, null));
		assertTrue(res2.has("systemInstruction"));
		assertEquals(
				"First sys line\nSecond sys line",
				res2.path("systemInstruction").path("parts").get(0).path("text").asString()
		);

		JsonNode contents = res2.path("contents");
		assertEquals(4, contents.size()); // null role (user), assistant, tool, user

		// Null role turn
		assertEquals("user", contents.get(0).path("role").asString());
		assertEquals("Null role defaults to user", contents.get(0).path("parts").get(0).path("text").asString());

		// Assistant turn
		assertEquals("model", contents.get(1).path("role").asString());
		assertEquals("Assistant array text", contents.get(1).path("parts").get(0).path("text").asString());
		assertEquals("valid_fn", contents.get(1).path("parts").get(1).path("functionCall").path("name").asString());

		// Tool turn with null name/content
		assertEquals("user", contents.get(2).path("role").asString());
		assertEquals(
				"tool_response",
				contents.get(2).path("parts").get(0).path("functionResponse").path("name").asString()
		);

		// User image parts
		assertEquals("user", contents.get(3).path("role").asString());
		assertEquals("", contents.get(3).path("parts").get(0).path("text").asString());
		assertEquals("image/jpeg", contents.get(3).path("parts").get(1).path("inlineData").path("mimeType").asString());
		assertEquals("123456", contents.get(3).path("parts").get(1).path("inlineData").path("data").asString());

		// tool_choice: none
		assertTrue(res2.has("toolConfig"));
		assertEquals("NONE", res2.path("toolConfig").path("functionCallingConfig").path("mode").asString());
	}

	@Test
	@DisplayName("tests mixed stop sequences, non-json response format, and invalid data URIs")
	void testsMixedStopAndInvalidDataUris() throws Exception {
		String body = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {
				      "role": "user",
				      "content": [
				        {"type": "image_url", "image_url": {"url": "data:not-valid-base64"}},
				        {"type": "image_url", "image_url": {"url": "data:image/png;base64"}}
				      ]
				    }
				  ],
				  "stop": ["STOP1", 123, "STOP2"],
				  "response_format": {"type": "text"},
				  "temperature": null,
				  "top_p": null,
				  "max_tokens": 0
				}""";

		JsonNode res = objectMapper.readTree(adapter.buildRequestBody(body, null));
		JsonNode genConfig = res.path("generationConfig");
		assertFalse(genConfig.has("temperature"));
		assertFalse(genConfig.has("topP"));
		assertFalse(genConfig.has("maxOutputTokens"));
		assertFalse(genConfig.has("responseMimeType"));
		assertEquals(2, genConfig.path("stopSequences").size());
		assertEquals("STOP1", genConfig.path("stopSequences").get(0).asString());
		assertEquals("STOP2", genConfig.path("stopSequences").get(1).asString());
		assertEquals(0, res.path("contents").get(0).path("parts").size());
	}
}
