package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted branch coverage test suite covering all remaining branch and decision conditions across all protocol
 * adapters and normalizers.
 */
@DisplayName("ProtocolBranchCoverage")
@SuppressWarnings("DataFlowIssue")
class ProtocolBranchCoverageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("UniversalToolNormalizer branch permutations for non-standard JSON and tool choice types")
	void universalToolNormalizerBranches() throws Exception {
		// Non-object, non-string, non-array tool choices (e.g. integer or boolean)
		JsonNode numChoice = objectMapper.readTree("123");
		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(numChoice, null, objectMapper));
		assertNotNull(UniversalToolNormalizer.toGeminiToolConfig(numChoice, objectMapper));

		JsonNode boolChoice = objectMapper.readTree("true");
		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(boolChoice, null, objectMapper));
		assertNotNull(UniversalToolNormalizer.toGeminiToolConfig(boolChoice, objectMapper));

		// JSON Schema where properties, items, required, enum, description have non-standard types
		String weirdSchemaJson = """
				{
				  "properties": "not-an-object",
				  "items": "not-an-object",
				  "required": "not-an-array",
				  "enum": "not-an-array",
				  "description": 12345,
				  "extraCustom": true
				}""";
		JsonNode weirdSchema = objectMapper.readTree(weirdSchemaJson);
		ObjectNode res = UniversalToolNormalizer.toGeminiParameters(weirdSchema, objectMapper);
		assertEquals("OBJECT", res.get("type").asString());
		assertTrue(res.get("extraCustom").asBoolean());
	}

	@Test
	@DisplayName("GeminiSseNormalizer multiple usage updates and missing args branches")
	void geminiSseNormalizerBranches() throws Exception {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "m", true);

		// First usage event
		normalizer.normalizeLine("data: {\"usageMetadata\": {\"promptTokenCount\": 10, \"candidatesTokenCount\": 5}}");
		assertEquals(10, normalizer.usage().promptTokens());
		assertEquals(5, normalizer.usage().completionTokens());

		// Second usage event (updating existing non-null tokens)
		normalizer.normalizeLine(
				"data: {\"usageMetadata\": {\"promptTokenCount\": 20, \"candidatesTokenCount\": 15, \"cachedContentTokenCount\": 8, \"thoughtsTokenCount\": 6}}");
		assertEquals(20, normalizer.usage().promptTokens());
		assertEquals(15, normalizer.usage().completionTokens());

		// Missing functionCall args node (missingNode vs null vs object)
		String missingArgs = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"functionCall\": {\"name\": \"test\"}}]}, \"finishReason\": \"STOP\"}]}";
		List<String> out = normalizer.normalizeLine(missingArgs);
		assertFalse(out.isEmpty());
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("DeepSeekSseNormalizer multiple usage updates")
	void deepSeekSseNormalizerBranches() {
		DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "m", true);

		// First usage
		normalizer.normalizeLine(
				"data: {\"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5, \"prompt_cache_hit_tokens\": 2}}");
		assertEquals(10, normalizer.usage().promptTokens());
		assertEquals(5, normalizer.usage().completionTokens());
		assertEquals(2L, normalizer.cachedTokens());

		// Second usage (updating non-null tokens)
		normalizer.normalizeLine(
				"data: {\"usage\": {\"prompt_tokens\": 30, \"completion_tokens\": 25, \"prompt_cache_hit_tokens\": 12}}");
		assertEquals(30, normalizer.usage().promptTokens());
		assertEquals(25, normalizer.usage().completionTokens());
		assertEquals(12L, normalizer.cachedTokens());
	}

	@Test
	@DisplayName("GeminiAdapter unary generateContent URL, null messages, and content variations")
	void geminiAdapterBranches() throws Exception {
		GeminiAdapter adapter = new GeminiAdapter(objectMapper);

		// URL containing :generateContent
		ProviderConfig unaryConfig = new ProviderConfig(
				"gemini-unary",
				ProviderType.GEMINI,
				URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"),
				new SensitiveString("key"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		assertEquals(
				"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
				adapter.buildUpstreamUrl(unaryConfig).toString()
		);

		// Request with null messages, null stop, null tools, null toolChoice, null responseFormat
		String nullProps = "{\"model\":\"m\"}";
		String trans1 = adapter.buildRequestBody(nullProps, null);
		assertNotNull(trans1);

		// Request with non-json json_schema, tool calls null array, empty array text
		String body = """
				{
				  "model": "m",
				  "messages": [
				    {"role": "system", "content": "   "},
				    {"role": "assistant", "content": "   ", "tool_calls": "not-array"},
				    {"role": "user", "content": [123, true, null]}
				  ],
				  "response_format": {"type": "json_schema"},
				  "stop": null,
				  "tools": "not-array"
				}""";
		String trans2 = adapter.buildRequestBody(body, null);
		assertNotNull(trans2);
	}

	@Test
	@DisplayName("AnthropicAdapter null messages, null tools, and baseUrl without slash")
	void anthropicAdapterBranches() throws Exception {
		AnthropicAdapter adapter = new AnthropicAdapter(objectMapper);

		// Base URL without slash
		ProviderConfig config = new ProviderConfig(
				"anthropic-noslash",
				ProviderType.ANTHROPIC,
				URI.create("https://api.anthropic.com"),
				new SensitiveString("sk-ant"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
		assertEquals("https://api.anthropic.com/v1/messages", adapter.buildUpstreamUrl(config).toString());

		// Null messages, null stop, null tools, assistant with non-array toolCalls
		String body = """
				{
				  "model": "claude-3-5",
				  "messages": [
				    {"role": "system", "content": "   "},
				    {"role": "assistant", "tool_calls": "not-array"},
				    {"role": "user", "content": null}
				  ],
				  "stop": null,
				  "tools": "not-array"
				}""";
		String trans = adapter.buildRequestBody(body, null);
		assertNotNull(trans);
	}

	@Test
	@DisplayName("DeepSeekAdapter null messages, null tools, and max_tokens <= 0")
	void deepSeekAdapterBranches() throws Exception {
		DeepSeekAdapter adapter = new DeepSeekAdapter(objectMapper);

		String body = """
				{
				  "model": "deepseek-chat",
				  "messages": [
				    {"role": "assistant", "tool_calls": "not-array"}
				  ],
				  "max_tokens": 0,
				  "tools": "not-array",
				  "thinking": "not-an-object"
				}""";
		String trans = adapter.buildRequestBody(body, null);
		assertNotNull(trans);
	}

	@Test
	@DisplayName("AnthropicSseNormalizer delta with missing or non-object delta and stop_reason")
	void anthropicSseNormalizerBranches() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		// message_delta with null delta
		normalizer.normalizeLine("event: message_delta\ndata: {\"type\": \"message_delta\"}");

		// message_delta with non-object delta
		normalizer.normalizeLine("event: message_delta\ndata: {\"type\": \"message_delta\", \"delta\": 123}");

		// message_delta with delta without stop_reason
		normalizer.normalizeLine(
				"event: message_delta\ndata: {\"type\": \"message_delta\", \"delta\": {\"other\": \"val\"}}");
	}

	@Test
	@DisplayName("DeepSeekAdapter full message fields and GeminiSseNormalizer empty text parts")
	void deepSeekAndGeminiAdditionalBranches() throws Exception {
		DeepSeekAdapter deepSeek = new DeepSeekAdapter(objectMapper);
		String fullBody = """
				{
				  "model": "deepseek-chat",
				  "messages": [
				    {
				      "role": "assistant",
				      "content": "Full content",
				      "name": "asst_1",
				      "tool_call_id": "c1",
				      "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "f"}}],
				      "reasoning_content": "reasoning"
				    }
				  ],
				  "max_tokens": 100,
				  "temperature": 0.5,
				  "top_p": 0.9,
				  "thinking": {"type": "enabled"}
				}""";
		String fullTrans = deepSeek.buildRequestBody(fullBody, null);
		assertNotNull(fullTrans);

		// GeminiSseNormalizer with empty thought text, empty text, and candidate without content
		GeminiSseNormalizer geminiNorm = new GeminiSseNormalizer(objectMapper, "m", false);
		geminiNorm.normalizeLine(
				"data: {\"candidates\": [{\"content\": {\"parts\": [{\"thought\": true, \"text\": \"\"}, {\"text\": \"\"}]}}]}");
		geminiNorm.normalizeLine("data: {\"candidates\": [{}]}");
		assertFalse(geminiNorm.isDone());
	}

	@Test
	@DisplayName("DeepSeekAdapter and GeminiAdapter model override and option branches")
	void adapterModelOverrideBranches() {
		DeepSeekAdapter deepSeek = new DeepSeekAdapter(objectMapper);
		String res1 = deepSeek.buildRequestBody(
				"{\"model\":\"deepseek-chat\",\"reasoning_effort\":\"low\"}",
				"deepseek-override"
		);
		assertTrue(res1.contains("deepseek-override"));
		assertTrue(res1.contains("reasoning_effort"));

		GeminiAdapter gemini = new GeminiAdapter(objectMapper);
		String res2 = gemini.buildRequestBody(
				"{\"model\":\"gemini-2.5-flash\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}]}",
				"gemini-override"
		);
		assertNotNull(res2);

		// AnthropicAdapter with disable_parallel_tool_use
		AnthropicAdapter anthropic = new AnthropicAdapter(objectMapper);
		String res3 = anthropic.buildRequestBody(
				"{\"model\":\"claude-3-5\",\"tool_choice\":\"auto\",\"parallel_tool_calls\":false,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}}]}",
				"claude-override"
		);
		assertTrue(res3.contains("disable_parallel_tool_use"));
	}

	@Test
	@DisplayName("GeminiAdapter and GeminiSseNormalizer exhaustive branch coverage")
	void geminiExhaustiveBranches() throws Exception {
		GeminiAdapter adapter = new GeminiAdapter(objectMapper);

		// 1. Data URI image in user multipart
		String imageBody = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [
				    {
				      "role": "user",
				      "content": [
				        {"type": "text", "text": "Describe this image:"},
				        {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="}}
				      ]
				    },
				    {
				      "role": "system",
				      "content": [
				        {"type": "text", "text": "Instruction 1"},
				        {"type": "text", "text": "Instruction 2"}
				      ]
				    },
				    {
				      "role": "unknown_custom_role",
				      "content": "Should default to user"
				    }
				  ],
				  "stop": "STOP_SEQ"
				}""";
		String imageRes = adapter.buildRequestBody(imageBody, null);
		assertTrue(imageRes.contains("inlineData"));
		assertTrue(imageRes.contains("image/png"));

		// 2. GeminiSseNormalizer finish reasons
		String[] reasons = {"MAX_TOKENS", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL", "CUSTOM_UNKNOWN"};
		for (String reason : reasons) {
			GeminiSseNormalizer norm = new GeminiSseNormalizer(objectMapper, "m", true);
			List<String> out = norm.normalizeLine("data: {\"candidates\": [{\"finishReason\": \"" + reason + "\"}]}");
			assertFalse(out.isEmpty());
			assertTrue(norm.isDone());
		}

		// 3. Upstream URL variants
		ProviderConfig streamDirect = new ProviderConfig(
				"g1",
				ProviderType.GEMINI,
				URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent"),
				new SensitiveString("k"),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1)
		);
		assertEquals(
				"https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent",
				adapter.buildUpstreamUrl(streamDirect).toString()
		);

		ProviderConfig modelsPath = new ProviderConfig(
				"g2", ProviderType.GEMINI, URI.create("https://generativelanguage.googleapis.com/v1beta/models"),
				new SensitiveString("k"), Duration.ofSeconds(1), Duration.ofSeconds(1)
		);
		assertTrue(adapter.buildUpstreamUrl(modelsPath).toString().contains("alt=sse"));

		ProviderConfig vertexConfig = new ProviderConfig(
				"v1",
				ProviderType.VERTEX_AI,
				URI.create(
						"https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/l/publishers/google/models/gemini"),
				new SensitiveString("k"),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1)
		);
		assertTrue(adapter.buildUpstreamUrl(vertexConfig).toString().contains("alt=sse"));
	}

	@Test
	@DisplayName("DeepSeekSseNormalizer standalone usage branches with includeUsage toggle")
	void deepSeekStandaloneUsageBranches() {
		// Standalone usage with missing choices
		DeepSeekSseNormalizer normDrop = new DeepSeekSseNormalizer(objectMapper, "m", false);
		assertTrue(normDrop.normalizeLine("data: {\"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}")
		                   .isEmpty());

		DeepSeekSseNormalizer normKeep = new DeepSeekSseNormalizer(objectMapper, "m", true);
		assertFalse(normKeep.normalizeLine("data: {\"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}")
		                    .isEmpty());

		// Standalone usage with empty array choices
		assertTrue(normDrop.normalizeLine(
				"data: {\"choices\": [], \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}").isEmpty());
		assertFalse(normKeep.normalizeLine(
				"data: {\"choices\": [], \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}").isEmpty());

		// Usage with choices non-empty (always preserved regardless of toggle)
		assertFalse(normDrop.normalizeLine(
				                    "data: {\"choices\": [{\"delta\": {\"content\": \"hi\"}}], \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}")
		                    .isEmpty());
	}
}
