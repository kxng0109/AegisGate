package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OllamaSseNormalizer}: newline delimited JSON mapping, token counts from the done line, and the
 * emitted OpenAI chunk shape.
 */
@DisplayName("OllamaSseNormalizer")
class OllamaSseNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("rewrites a full Ollama NDJSON stream into OpenAI chunks")
	void rewritesFullStream() throws Exception {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "fallback", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine(deltaLine("Hello")));
		lines.addAll(normalizer.normalizeLine(deltaLine(" world")));
		lines.addAll(normalizer.normalizeLine(doneLine(26, 282)));

		assertEquals(4, lines.size(), "two content chunks, the final chunk, and DONE");
		JsonNode first = chunk(lines.get(0));
		assertEquals("Hello", first.path("choices").get(0).path("delta").path("content").asString());
		JsonNode second = chunk(lines.get(1));
		assertEquals(" world", second.path("choices").get(0).path("delta").path("content").asString());
		JsonNode finalChunk = chunk(lines.get(2));
		assertEquals("stop", finalChunk.path("choices").get(0).path("finish_reason").asString());
		assertEquals("data: [DONE]", lines.get(3));

		assertTrue(normalizer.isDone());
		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(26, usage.promptTokens());
		assertEquals(282, usage.completionTokens());
		assertEquals("llama3.2", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("emits the usage chunk before DONE when the client asked for it")
	void emitsUsageWhenRequested() throws Exception {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "fallback", true);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine(deltaLine("Hello")));
		lines.addAll(normalizer.normalizeLine(doneLine(26, 282)));

		assertEquals(4, lines.size(), "content chunk, usage chunk, final chunk, and DONE");
		JsonNode usage = chunk(lines.get(1));
		assertEquals(0, usage.get("choices").size());
		assertEquals(26, usage.path("usage").path("prompt_tokens").asLong());
		assertEquals(282, usage.path("usage").path("completion_tokens").asLong());
		assertEquals(308, usage.path("usage").path("total_tokens").asLong());
	}

	@Test
	@DisplayName("ignores empty content fragments and unparseable lines")
	void ignoresEmptyFragments() throws Exception {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine(
				"{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}"));
		lines.addAll(normalizer.normalizeLine("not json"));
		lines.addAll(normalizer.normalizeLine(doneLine(1, 1)));

		assertEquals(2, lines.size(), "only the final chunk and DONE");
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("falls back to the requested model when lines carry no model")
	void fallsBackToRequestedModel() {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "llama3.2", false);
		normalizer.normalizeLine("{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":false}");
		assertEquals("llama3.2", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("no usage is reported when the stream never finished")
	void noUsageWhenNotFinished() {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "m", false);
		normalizer.normalizeLine(deltaLine("Hello"));
		assertFalse(normalizer.isDone());
		assertNull(normalizer.usage());
	}

	@Test
	@DisplayName("no lines are produced after the stream finished")
	void nothingAfterDone() {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "m", false);
		normalizer.normalizeLine(doneLine(1, 1));
		assertEquals(List.of(), normalizer.normalizeLine(deltaLine("late")));
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("blank and non object lines are ignored")
	void blankAndNonObjectLinesIgnored() {
		OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(), normalizer.normalizeLine("   "));
		assertEquals(List.of(), normalizer.normalizeLine("42"));
	}

	private static String deltaLine(String content) {
		return "{\"model\":\"llama3.2\",\"created_at\":\"2023-08-04T08:52:19Z\","
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"done\":false}";
	}

	private static String doneLine(long promptEval, long eval) {
		return "{\"model\":\"llama3.2\",\"created_at\":\"2023-08-04T08:52:19Z\","
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
				+ "\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":" + promptEval
				+ ",\"eval_count\":" + eval + ",\"eval_duration\":1}";
	}

	private static JsonNode chunk(String line) throws Exception {
		assertTrue(line.startsWith("data: "));
		return new ObjectMapper().readTree(line.substring("data: ".length()));
	}
}