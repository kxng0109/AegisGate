package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeepSeekSseNormalizer")
@SuppressWarnings("DataFlowIssue")
class DeepSeekSseNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("normalizes reasoning deltas, content deltas, and prompt cache hit telemetry")
	void normalizesDeepSeekStreaming() throws Exception {
		DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "deepseek-reasoner", true);

		String reasoningChunk = "data: {\"id\":\"chunk-1\",\"choices\":[{\"delta\":{\"reasoning_content\":\"Analyzing math step by step...\"}}],\"model\":\"deepseek-reasoner\"}";
		List<String> out1 = normalizer.normalizeLine(reasoningChunk);
		assertEquals(1, out1.size());
		assertTrue(out1.getFirst().contains("reasoning_content"));

		String contentChunk = "data: {\"id\":\"chunk-2\",\"choices\":[{\"delta\":{\"content\":\"The derivative is cos(x).\"}}]}";
		List<String> out2 = normalizer.normalizeLine(contentChunk);
		assertEquals(1, out2.size());
		assertTrue(out2.getFirst().contains("The derivative is cos(x)."));

		String terminalChunk = """
				data: {
				  "id": "chunk-3",
				  "choices": [{"delta": {}, "finish_reason": "stop"}],
				  "usage": {
				    "prompt_tokens": 128,
				    "prompt_cache_hit_tokens": 64,
				    "prompt_cache_miss_tokens": 64,
				    "completion_tokens": 50,
				    "total_tokens": 178,
				    "completion_tokens_details": {
				      "reasoning_tokens": 30
				    }
				  }
				}""";
		List<String> out3 = normalizer.normalizeLine(terminalChunk);
		assertEquals(1, out3.size());

		List<String> outDone = normalizer.normalizeLine("data: [DONE]");
		assertEquals(1, outDone.size());
		assertEquals("data: [DONE]", outDone.getFirst());
		assertTrue(normalizer.isDone());

		assertNotNull(normalizer.usage());
		assertEquals(128, normalizer.usage().promptTokens());
		assertEquals(50, normalizer.usage().completionTokens());
		assertEquals(64L, normalizer.cachedTokens());
		assertEquals(30L, normalizer.reasoningTokens());
		assertEquals("deepseek-reasoner", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("tests edge branches for empty lines, malformed payloads, and fallback models")
	void testsEdgeBranches() {
		DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "fallback-deepseek", false);
		assertNull(normalizer.usage());
		assertNull(normalizer.cachedTokens());
		assertNull(normalizer.reasoningTokens());
		assertEquals("fallback-deepseek", normalizer.upstreamModel());

		assertTrue(normalizer.normalizeLine("").isEmpty());
		assertTrue(normalizer.normalizeLine("event: ping").isEmpty());
		assertTrue(normalizer.normalizeLine("data: ").isEmpty());
		assertTrue(normalizer.normalizeLine("data: not-json").isEmpty());
		assertTrue(normalizer.normalizeLine("data: [1, 2, 3]").isEmpty());

		// Normalize line with usage without completion_tokens_details (when includeUsageInResponse = false, standalone usage is dropped)
		String usageNoDetails = "data: {\"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5}}";
		List<String> out = normalizer.normalizeLine(usageNoDetails);
		assertTrue(out.isEmpty());
		assertNotNull(normalizer.usage());
		assertEquals(10, normalizer.usage().promptTokens());
		assertEquals(5, normalizer.usage().completionTokens());
		assertNull(normalizer.reasoningTokens());

		// When includeUsageInResponse = true, standalone usage is forwarded
		DeepSeekSseNormalizer includeUsageNorm = new DeepSeekSseNormalizer(objectMapper, "fallback", true);
		List<String> outWithUsage = includeUsageNorm.normalizeLine(usageNoDetails);
		assertEquals(1, outWithUsage.size());
		assertTrue(outWithUsage.getFirst().contains("usage"));

		// Done sentinel and subsequent lines
		assertFalse(normalizer.isDone());
		assertEquals(List.of("data: [DONE]"), normalizer.normalizeLine("data: [DONE]"));
		assertTrue(normalizer.isDone());
		assertTrue(normalizer.normalizeLine("data: {\"id\": \"after-done\"}").isEmpty());

		// Model non-string and usage non-object
		DeepSeekSseNormalizer nonStringModelNorm = new DeepSeekSseNormalizer(objectMapper, "fallback", false);
		nonStringModelNorm.normalizeLine("data: {\"model\": 123, \"usage\": \"invalid\"}");
		assertEquals("fallback", nonStringModelNorm.upstreamModel());
		assertNull(nonStringModelNorm.usage());

		// Partial usage (only prompt or only completion)
		DeepSeekSseNormalizer partialNorm1 = new DeepSeekSseNormalizer(objectMapper, "fallback", false);
		partialNorm1.normalizeLine("data: {\"usage\": {\"prompt_tokens\": 42}}");
		assertNotNull(partialNorm1.usage());
		assertEquals(42, partialNorm1.usage().promptTokens());
		assertEquals(0, partialNorm1.usage().completionTokens());

		DeepSeekSseNormalizer partialNorm2 = new DeepSeekSseNormalizer(objectMapper, "fallback", false);
		partialNorm2.normalizeLine("data: {\"usage\": {\"completion_tokens\": 99}}");
		assertNotNull(partialNorm2.usage());
		assertEquals(0, partialNorm2.usage().promptTokens());
		assertEquals(99, partialNorm2.usage().completionTokens());
	}
}
