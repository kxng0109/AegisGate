package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnthropicSseNormalizer}: the two line SSE event mapping, cumulative usage capture, tolerance of
 * ping and unknown events, and the emitted OpenAI chunk shape.
 */
@DisplayName("AnthropicSseNormalizer")
class AnthropicSseNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("rewrites a full Anthropic stream into OpenAI chunks")
	void rewritesFullStream() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: message_start"));
		lines.addAll(normalizer.normalizeLine(messageStart()));
		lines.addAll(normalizer.normalizeLine("event: content_block_start"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(delta("Hello")));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(delta(" world")));
		lines.addAll(normalizer.normalizeLine("event: message_delta"));
		lines.addAll(normalizer.normalizeLine(messageDelta(15)));
		lines.addAll(normalizer.normalizeLine("event: message_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"message_stop\"}"));

		assertEquals(4, lines.size(), "two content chunks, the final chunk, and DONE");
		JsonNode first = chunk(lines.getFirst());
		assertEquals("Hello", first.path("choices").get(0).path("delta").path("content").asString());
		JsonNode second = chunk(lines.get(1));
		assertEquals(" world", second.path("choices").get(0).path("delta").path("content").asString());
		JsonNode finalChunk = chunk(lines.get(2));
		assertEquals("stop", finalChunk.path("choices").get(0).path("finish_reason").asString());
		assertEquals("data: [DONE]", lines.get(3));

		assertTrue(normalizer.isDone());
		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(25, usage.promptTokens(), "input tokens come from message_start");
		assertEquals(15, usage.completionTokens(), "output tokens are the cumulative message_delta count");
		assertEquals("claude-sonnet-5", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("emits the usage chunk before DONE when the client asked for it")
	void emitsUsageWhenRequested() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback", true);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: message_start"));
		lines.addAll(normalizer.normalizeLine(messageStart()));
		lines.addAll(normalizer.normalizeLine("event: message_delta"));
		lines.addAll(normalizer.normalizeLine(messageDelta(7)));
		lines.addAll(normalizer.normalizeLine("event: message_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"message_stop\"}"));

		assertEquals(3, lines.size(), "usage chunk, final chunk, and DONE");
		JsonNode usage = chunk(lines.getFirst());
		assertEquals(0, usage.get("choices").size());
		assertEquals(25, usage.path("usage").path("prompt_tokens").asLong());
		assertEquals(7, usage.path("usage").path("completion_tokens").asLong());
	}

	@Test
	@DisplayName("ignores ping and unknown event types")
	void ignoresPingAndUnknownEvents() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: ping"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"ping\"}"));
		lines.addAll(normalizer.normalizeLine("event: brand_new_event"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"brand_new_event\",\"payload\":{}}"));
		lines.addAll(normalizer.normalizeLine("event: message_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"message_stop\"}"));

		assertEquals(List.of(OpenAiSseLine.DONE), lines.subList(lines.size() - 1, lines.size()));
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("drops thinking deltas but keeps text deltas")
	void dropsThinkingDeltas() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"secret\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(delta("answer")));

		assertEquals(1, lines.size(), "only the text delta is relayed");
	}

	@Test
	@DisplayName("unparseable data lines are ignored")
	void ignoresUnparseableLines() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(), normalizer.normalizeLine("data: {not json"));
	}

	@Test
	@DisplayName("a data line without a type is ignored")
	void ignoresDataWithoutType() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"foo\":1}"));
		assertEquals(List.of(), normalizer.normalizeLine("not a data line"));
		assertEquals(List.of(), normalizer.normalizeLine(""));
	}

	@Test
	@DisplayName("message start without usage reports zero prompt tokens")
	void messageStartWithoutUsage() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		normalizer.normalizeLine("event: message_start");
		normalizer.normalizeLine(
				"data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-5\"}}");
		normalizer.normalizeLine("event: message_delta");
		normalizer.normalizeLine(
				"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
		normalizer.normalizeLine("event: message_stop");
		normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

		assertTrue(normalizer.isDone());
		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(0, usage.promptTokens());
		assertEquals(0, usage.completionTokens());
	}

	@Test
	@DisplayName("no usage chunk is emitted when usage is unknown even if requested")
	void noUsageChunkWhenUnknown() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", true);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: message_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"message_stop\"}"));

		assertEquals(List.of(OpenAiSseLine.DONE), lines.subList(lines.size() - 1, lines.size()));
		assertEquals(1, lines.size() - 1, "the final chunk but no usage chunk");
	}

	@Test
	@DisplayName("a blank event name is tolerated")
	void blankEventTolerated() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(), normalizer.normalizeLine("event:"));
	}

	@Test
	@DisplayName("empty text deltas are ignored")
	void emptyTextDeltaIgnored() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}"));
		assertEquals(List.of(), lines);
	}

	@Test
	@DisplayName("no lines are produced after the stream finished")
	void nothingAfterDone() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		normalizer.normalizeLine("event: message_stop");
		normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");
		assertEquals(List.of(), normalizer.normalizeLine("event: content_block_delta"));
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("usage is null before the stream finishes")
	void usageNullBeforeDone() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		assertNull(normalizer.usage());
	}

	@Test
	@DisplayName("message start with a non textual model keeps the fallback")
	void messageStartWithoutModelText() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback", false);

		normalizer.normalizeLine("event: message_start");
		normalizer.normalizeLine("data: {\"type\":\"message_start\",\"message\":{\"model\":123}}");
		normalizer.normalizeLine("event: message_stop");
		normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

		assertEquals("fallback", normalizer.upstreamModel());
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("a second message start cannot lower the input count")
	void secondMessageStartKeepsCount() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		normalizer.normalizeLine("event: message_start");
		normalizer.normalizeLine(messageStart());
		normalizer.normalizeLine("event: message_start");
		normalizer.normalizeLine("data: {\"type\":\"message_start\",\"message\":{}}");
		normalizer.normalizeLine("event: message_stop");
		normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(25, usage.promptTokens());
	}

	@Test
	@DisplayName("a data line that is not a JSON object is ignored")
	void nonObjectDataIgnored() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(), normalizer.normalizeLine("data: [1,2,3]"));
	}

	@Test
	@DisplayName("a message start without a message object is ignored")
	void messageStartWithoutMessage() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback", false);

		assertEquals(List.of(), normalizer.normalizeLine("event: message_start"));
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"type\":\"message_start\"}"));
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"type\":\"message_start\",\"message\":42}"));

		assertEquals("fallback", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("an empty text model keeps the fallback")
	void emptyTextModelKeepsFallback() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback", false);

		normalizer.normalizeLine("event: message_start");
		normalizer.normalizeLine(
				"data: {\"type\":\"message_start\",\"message\":{\"model\":\"\",\"usage\":{\"input_tokens\":9}}}");

		assertEquals("fallback", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("a message delta with a malformed usage field is ignored")
	void malformedMessageDeltaIgnored() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		normalizer.normalizeLine("event: message_delta");
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"type\":\"message_delta\",\"usage\":5}"));
		normalizer.normalizeLine("event: message_delta");
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"type\":\"message_delta\",\"usage\":{}}"));
	}

	@Test
	@DisplayName("output only streams report zero input tokens")
	void outputOnlyStream() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		normalizer.normalizeLine("event: message_delta");
		normalizer.normalizeLine(messageDelta(5));
		normalizer.normalizeLine("event: message_stop");
		normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(0, usage.promptTokens());
		assertEquals(5, usage.completionTokens());
	}

	private static String messageStart() {
		return "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
				+ "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5\","
				+ "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}";
	}

	private static String delta(String text) {
		return "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
				+ text + "\"}}";
	}

	private static String messageDelta(long outputTokens) {
		return "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
				+ "\"usage\":{\"output_tokens\":" + outputTokens + "}}";
	}

	private static JsonNode chunk(String line) {
		assertTrue(line.startsWith("data: "));
		return new ObjectMapper().readTree(line.substring("data: ".length()));
	}
}