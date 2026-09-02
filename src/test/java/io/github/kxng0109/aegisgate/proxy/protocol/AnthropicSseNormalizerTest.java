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
@SuppressWarnings("DataFlowIssue")
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
		assertEquals("data: [DONE]", lines.getLast());

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
	@DisplayName("translates thinking deltas to reasoning_content and text deltas to content")
	void translatesThinkingDeltas() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"step by step reasoning\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(delta("answer")));

		assertEquals(2, lines.size());
		assertTrue(lines.getFirst().contains("reasoning_content"));
		assertTrue(lines.get(1).contains("answer"));
	}

	@Test
	@DisplayName("translates streaming tool_use content blocks and input_json_delta")
	void translatesStreamingToolUse() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-7-sonnet", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("event: content_block_start"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"get_weather\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\": \\\"\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"Paris\\\"}\"}}"));
		lines.addAll(normalizer.normalizeLine("event: content_block_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"content_block_stop\",\"index\":0}"));
		lines.addAll(normalizer.normalizeLine("event: message_delta"));
		lines.addAll(normalizer.normalizeLine(
				"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20}}"));
		lines.addAll(normalizer.normalizeLine("event: message_stop"));
		lines.addAll(normalizer.normalizeLine("data: {\"type\":\"message_stop\"}"));

		assertEquals(5, lines.size()); // header + 2 arg deltas + finished(tool_calls) + DONE
		JsonNode headerChunk = chunk(lines.getFirst());
		assertEquals(
				"get_weather",
				headerChunk.path("choices").get(0).path("delta").path("tool_calls").get(0).path("function")
				           .path("name").asString()
		);

		JsonNode finishChunk = chunk(lines.get(3));
		assertEquals("tool_calls", finishChunk.path("choices").get(0).path("finish_reason").asString());
		assertEquals("data: [DONE]", lines.getLast());
		assertTrue(normalizer.isDone());
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

	@Test
	@DisplayName("tests remaining edge branches for Anthropic SSE normalizer")
	void testsRemainingEdgeBranches() {
		AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "fallback-model", true);

		// contentBlockStart with non-tool_use type
		List<String> textBlockStart = normalizer.normalizeLine(
				"data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}");
		assertTrue(textBlockStart.isEmpty());

		// contentDelta with empty thinking delta
		List<String> emptyThinking = normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"\"}}");
		assertTrue(emptyThinking.isEmpty());

		// contentDelta with unknown type
		List<String> unknownDelta = normalizer.normalizeLine(
				"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"unknown_delta\"}}");
		assertTrue(unknownDelta.isEmpty());

		// messageDelta with stop_sequence, max_tokens, and custom unknown stop_reason
		normalizer.normalizeLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}");
		normalizer.normalizeLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"stop_sequence\"}}");
		normalizer.normalizeLine(
				"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"unknown_custom_reason\"}}");

		// messageStart with tokens
		normalizer.normalizeLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50}}}");

		// messageStop
		List<String> stopLines = normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");
		assertEquals(3, stopLines.size()); // usage, finished, DONE

		// Usage with only input tokens
		AnthropicSseNormalizer inputOnly = new AnthropicSseNormalizer(objectMapper, "fallback", false);
		inputOnly.normalizeLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":40}}}");
		inputOnly.normalizeLine("data: {\"type\":\"message_stop\"}");
		assertNotNull(inputOnly.usage());
		assertEquals(40, inputOnly.usage().promptTokens());
		assertEquals(0, inputOnly.usage().completionTokens());
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