package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GeminiSseNormalizer")
@SuppressWarnings("DataFlowIssue")
class GeminiSseNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("normalizes text delta chunks into OpenAI streaming format")
	void normalizesTextDeltas() throws Exception {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "gemini-2.5-flash", true);

		String line1 = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hello \"}], \"role\": \"model\"}, \"finishReason\": null, \"index\": 0}], \"modelVersion\": \"gemini-2.5-flash\"}";
		List<String> output1 = normalizer.normalizeLine(line1);

		assertEquals(1, output1.size());
		assertTrue(output1.getFirst().startsWith("data: "));
		JsonNode chunk1 = objectMapper.readTree(output1.getFirst().substring(6));
		assertEquals("Hello ", chunk1.path("choices").get(0).path("delta").path("content").asString());
		assertEquals("gemini-2.5-flash", chunk1.path("model").asString());
		assertFalse(normalizer.isDone());

		String line2 = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"world!\"}], \"role\": \"model\"}, \"finishReason\": \"STOP\", \"index\": 0}], \"usageMetadata\": {\"promptTokenCount\": 12, \"candidatesTokenCount\": 8, \"totalTokenCount\": 20}}";
		List<String> output2 = normalizer.normalizeLine(line2);

		assertEquals(4, output2.size()); // delta + usage + finish + [DONE]
		JsonNode deltaChunk = objectMapper.readTree(output2.getFirst().substring(6));
		assertEquals("world!", deltaChunk.path("choices").get(0).path("delta").path("content").asString());

		JsonNode usageChunk = objectMapper.readTree(output2.get(1).substring(6));
		assertEquals(12, usageChunk.path("usage").path("prompt_tokens").asInt());
		assertEquals(8, usageChunk.path("usage").path("completion_tokens").asInt());

		JsonNode finishChunk = objectMapper.readTree(output2.get(2).substring(6));
		assertEquals("stop", finishChunk.path("choices").get(0).path("finish_reason").asString());

		assertEquals("data: [DONE]", output2.getLast());
		assertTrue(normalizer.isDone());

		assertNotNull(normalizer.usage());
		assertEquals(12, normalizer.usage().promptTokens());
		assertEquals(8, normalizer.usage().completionTokens());
	}

	@Test
	@DisplayName("normalizes reasoning thoughts into delta.reasoning_content")
	void normalizesReasoningThoughts() throws Exception {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "gemini-2.5-flash", false);

		String thoughtLine = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"thought\": true, \"text\": \"Analyzing the user question...\"}], \"role\": \"model\"}}]}";
		List<String> output = normalizer.normalizeLine(thoughtLine);

		assertEquals(1, output.size());
		JsonNode chunk = objectMapper.readTree(output.getFirst().substring(6));
		assertEquals(
				"Analyzing the user question...",
				chunk.path("choices").get(0).path("delta").path("reasoning_content").asString()
		);
		assertFalse(chunk.path("choices").get(0).path("delta").has("content"));
	}

	@Test
	@DisplayName("normalizes functionCall into delta.tool_calls with synthetic IDs")
	void normalizesFunctionCall() throws Exception {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "gemini-2.5-flash", false);

		String funcCallLine = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"functionCall\": {\"name\": \"get_stock_quote\", \"args\": {\"symbol\": \"AAPL\"}}}], \"role\": \"model\"}, \"finishReason\": \"STOP\"}]}";
		List<String> output = normalizer.normalizeLine(funcCallLine);

		assertEquals(3, output.size()); // tool call + finish + [DONE]
		JsonNode toolChunk = objectMapper.readTree(output.getFirst().substring(6));
		JsonNode toolCall = toolChunk.path("choices").get(0).path("delta").path("tool_calls").get(0);
		assertEquals("get_stock_quote", toolCall.path("function").path("name").asString());
		assertTrue(toolCall.path("id").asString().startsWith("call_gen_"));
		assertTrue(toolCall.path("function").path("arguments").asString().contains("AAPL"));

		JsonNode finishChunk = objectMapper.readTree(output.get(1).substring(6));
		assertEquals("tool_calls", finishChunk.path("choices").get(0).path("finish_reason").asString());
		assertEquals("data: [DONE]", output.getLast());
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("maps Gemini finish reasons to standard OpenAI finish reasons")
	void mapsFinishReasons() {
		GeminiSseNormalizer normalizerMax = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outMax = normalizerMax.normalizeLine("data: {\"candidates\": [{\"finishReason\": \"MAX_TOKENS\"}]}");
		assertTrue(outMax.getFirst().contains("\"finish_reason\":\"length\""));

		GeminiSseNormalizer normalizerSafety = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outSafety = normalizerSafety.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"SAFETY\"}]}");
		assertTrue(outSafety.getFirst().contains("\"finish_reason\":\"content_filter\""));

		GeminiSseNormalizer normalizerMalformed = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outMalformed = normalizerMalformed.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"MALFORMED_FUNCTION_CALL\"}]}");
		assertTrue(outMalformed.getFirst().contains("\"finish_reason\":\"tool_calls\""));

		GeminiSseNormalizer normalizerRecitation = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outRec = normalizerRecitation.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"RECITATION\"}]}");
		assertTrue(outRec.getFirst().contains("\"finish_reason\":\"content_filter\""));

		GeminiSseNormalizer normalizerBlocklist = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outBlock = normalizerBlocklist.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"BLOCKLIST\"}]}");
		assertTrue(outBlock.getFirst().contains("\"finish_reason\":\"content_filter\""));

		GeminiSseNormalizer normalizerProhibited = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outProhib = normalizerProhibited.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"PROHIBITED_CONTENT\"}]}");
		assertTrue(outProhib.getFirst().contains("\"finish_reason\":\"content_filter\""));

		GeminiSseNormalizer normalizerSpii = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outSpii = normalizerSpii.normalizeLine("data: {\"candidates\": [{\"finishReason\": \"SPII\"}]}");
		assertTrue(outSpii.getFirst().contains("\"finish_reason\":\"content_filter\""));

		GeminiSseNormalizer normalizerBlankFinish = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outBlankFinish = normalizerBlankFinish.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"   \"}]}");
		assertTrue(outBlankFinish.isEmpty());
		assertFalse(normalizerBlankFinish.isDone());

		GeminiSseNormalizer normalizerNullFinish = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outNullFinish = normalizerNullFinish.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": null}]}");
		assertTrue(outNullFinish.isEmpty());
		assertFalse(normalizerNullFinish.isDone());

		GeminiSseNormalizer normalizerUnknown = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> outUnknown = normalizerUnknown.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"UNKNOWN_CUSTOM\"}]}");
		assertTrue(outUnknown.getFirst().contains("\"finish_reason\":\"stop\""));
	}

	@Test
	@DisplayName("tests edge branches for empty lines, malformed JSON, and usage parsing")
	void testsEdgeBranches() throws Exception {
		GeminiSseNormalizer normalizer = new GeminiSseNormalizer(objectMapper, "fallback-model", true);
		assertNull(normalizer.usage());
		assertEquals("fallback-model", normalizer.upstreamModel());

		// Non-data, empty, and invalid lines
		assertTrue(normalizer.normalizeLine("").isEmpty());
		assertTrue(normalizer.normalizeLine("event: ping").isEmpty());
		assertTrue(normalizer.normalizeLine("data: ").isEmpty());
		assertTrue(normalizer.normalizeLine("data: not-a-json").isEmpty());
		assertTrue(normalizer.normalizeLine("data: [1, 2, 3]").isEmpty());

		// Candidate with empty parts, empty thought, and empty text
		String emptyPartsLine = """
				data: {
				  "modelVersion": "gemini-custom",
				  "usageMetadata": {
				    "promptTokenCount": 100,
				    "candidatesTokenCount": 50,
				    "cachedContentTokenCount": 20,
				    "thoughtsTokenCount": 15
				  },
				  "candidates": [
				    {
				      "content": {
				        "parts": [
				          {"thought": true, "text": ""},
				          {"text": ""},
				          {"other": "value"}
				        ]
				      },
				      "finishReason": null
				    }
				  ]
				}""";
		List<String> emptyPartsOut = normalizer.normalizeLine(emptyPartsLine);
		assertTrue(emptyPartsOut.isEmpty());
		assertEquals("gemini-custom", normalizer.upstreamModel());

		// Non-string modelVersion and non-object usageMetadata
		normalizer.normalizeLine("data: {\"modelVersion\": 123, \"usageMetadata\": \"invalid\"}");
		assertEquals("gemini-custom", normalizer.upstreamModel());

		// Non-array candidates and non-array parts
		assertTrue(normalizer.normalizeLine("data: {\"candidates\": \"not-an-array\"}").isEmpty());
		assertTrue(normalizer.normalizeLine("data: {\"candidates\": [{\"content\": {\"parts\": 123}}]}").isEmpty());

		// Candidate finish reason with includeUsage=true but usage() is null
		GeminiSseNormalizer includeUsageNullNorm = new GeminiSseNormalizer(objectMapper, "m", true);
		List<String> nullUsageOut = includeUsageNullNorm.normalizeLine(
				"data: {\"candidates\": [{\"finishReason\": \"STOP\"}]}");
		assertEquals(2, nullUsageOut.size()); // finish + [DONE]
		assertTrue(nullUsageOut.getFirst().contains("\"finish_reason\":\"stop\""));
		assertEquals("data: [DONE]", nullUsageOut.getLast());
		assertTrue(emptyPartsOut.isEmpty());
		assertEquals("gemini-custom", normalizer.upstreamModel());

		// Function call with null args
		String funcCallNullArgs = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"functionCall\": {\"name\": \"test_fn\"}}]}, \"finishReason\": \"STOP\"}]}";
		List<String> funcOut = normalizer.normalizeLine(funcCallNullArgs);
		assertFalse(funcOut.isEmpty());
		assertTrue(funcOut.getFirst().contains("\"arguments\":\"{}\""));
		assertTrue(normalizer.isDone());

		// Normalize line after done
		assertTrue(normalizer.normalizeLine("data: {\"text\": \"after done\"}").isEmpty());

		// Gemini finish reason without usage info
		GeminiSseNormalizer noUsageNorm = new GeminiSseNormalizer(objectMapper, "m", false);
		List<String> noUsageOut = noUsageNorm.normalizeLine("data: {\"candidates\": [{\"finishReason\": \"STOP\"}]}");
		assertEquals(2, noUsageOut.size()); // finish + [DONE]
		assertNull(noUsageNorm.usage());

		// Usage info with only input tokens
		GeminiSseNormalizer inputOnlyNorm = new GeminiSseNormalizer(objectMapper, "m", true);
		inputOnlyNorm.normalizeLine("data: {\"usageMetadata\": {\"promptTokenCount\": 45}}");
		assertNotNull(inputOnlyNorm.usage());
		assertEquals(45, inputOnlyNorm.usage().promptTokens());
		assertEquals(0, inputOnlyNorm.usage().completionTokens());

		// Usage info with only output tokens
		GeminiSseNormalizer outputOnlyNorm = new GeminiSseNormalizer(objectMapper, "m", true);
		outputOnlyNorm.normalizeLine("data: {\"usageMetadata\": {\"candidatesTokenCount\": 25}}");
		assertNotNull(outputOnlyNorm.usage());
		assertEquals(0, outputOnlyNorm.usage().promptTokens());
		assertEquals(25, outputOnlyNorm.usage().completionTokens());
	}
}
