package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiSseNormalizer}: pass through relay, usage capture, usage chunk gating, and the terminal
 * marker.
 */
@DisplayName("OpenAiSseNormalizer")
class OpenAiSseNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("relays every content line untouched and stops at DONE")
	void relaysContentAndStops() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine("data: {\"delta\":{\"content\":\"hello\"}}"));
		lines.addAll(normalizer.normalizeLine("data: [DONE]"));

		assertEquals(
				List.of(
						"data: {\"delta\":{\"content\":\"hello\"}}",
						"data: [DONE]"
				), lines
		);
		assertTrue(normalizer.isDone());
	}

	@Test
	@DisplayName("captures the usage chunk and drops it when the client did not ask")
	void capturesUsageWithoutForwarding() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);

		List<String> lines = new ArrayList<>();
		lines.addAll(normalizer.normalizeLine(usageChunk(10, 5)));
		lines.addAll(normalizer.normalizeLine("data: [DONE]"));

		assertEquals(List.of("data: [DONE]"), lines, "the usage chunk must not reach the client");
		SseNormalizer.UsageInfo usage = normalizer.usage();
		assertNotNull(usage);
		assertEquals(10, usage.promptTokens());
		assertEquals(5, usage.completionTokens());
	}

	@Test
	@DisplayName("forwards the usage chunk when the client asked for it")
	void forwardsUsageWhenRequested() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", true);

		List<String> lines = normalizer.normalizeLine(usageChunk(10, 5));

		assertEquals(1, lines.size());
		assertTrue(lines.getFirst().startsWith("data: "));
		JsonNode node = objectMapper.readTree(lines.getFirst().substring("data: ".length()));
		assertEquals(0, node.get("choices").size());
		assertEquals(15, node.path("usage").path("total_tokens").asLong());
	}

	@Test
	@DisplayName("captures the model id reported by the provider")
	void capturesUpstreamModel() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "fallback", false);

		normalizer.normalizeLine(
				"data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-5.6-sol\",\"choices\":[]}");

		assertEquals("gpt-5.6-sol", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("falls back to the requested model when the provider never reports one")
	void fallsBackToRequestedModel() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "gpt-5.6-luna", false);
		normalizer.normalizeLine("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");
		assertEquals("gpt-5.6-luna", normalizer.upstreamModel());
	}

	@Test
	@DisplayName("no usage is reported when the stream carried none")
	void noUsageWithoutChunk() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);
		normalizer.normalizeLine("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");
		normalizer.normalizeLine("data: [DONE]");
		assertNull(normalizer.usage());
	}

	@Test
	@DisplayName("keep alive comments and blank lines pass through")
	void keepAlivesPassThrough() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);
		assertEquals(List.of(": keep-alive"), normalizer.normalizeLine(": keep-alive"));
		assertEquals(List.of(), normalizer.normalizeLine(""));
		assertEquals(
				List.of("data: {not json"),
				normalizer.normalizeLine("data: {not json"),
				"unparseable lines are relayed untouched, they are not usage chunks"
		);
		assertEquals(List.of("data: [1,2,3]"), normalizer.normalizeLine("data: [1,2,3]"));
	}

	@Test
	@DisplayName("a data line without usage or choices passes through")
	void plainDataLinePassesThrough() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);
		assertEquals(
				List.of("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\"}"),
				normalizer.normalizeLine("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\"}")
		);
	}

	@Test
	@DisplayName("no further lines are produced once done")
	void ignoresLinesAfterDone() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);
		normalizer.normalizeLine("data: [DONE]");
		assertEquals(List.of(), normalizer.normalizeLine("data: {\"choices\":[]}"));
	}

	@Test
	@DisplayName("lines that only resemble usage chunks are relayed untouched")
	void nearUsageChunksPassThrough() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);

		assertEquals(
				List.of("data: {\"id\":\"x\",\"choices\":[{\"index\":0}]}"),
				normalizer.normalizeLine("data: {\"id\":\"x\",\"choices\":[{\"index\":0}]}"),
				"a non empty choices array is not a usage chunk"
		);
		assertEquals(
				List.of("data: {\"id\":\"x\",\"usage\":\"string\"}"),
				normalizer.normalizeLine("data: {\"id\":\"x\",\"usage\":\"string\"}"),
				"a non object usage field is not a usage chunk"
		);
		assertEquals(
				List.of("data: {\"id\":\"x\",\"usage\":{}}"),
				normalizer.normalizeLine("data: {\"id\":\"x\",\"usage\":{}}"),
				"a usage field without a choices array is relayed"
		);
		assertEquals(
				List.of("data: {\"id\":\"x\",\"usage\":{},\"choices\":\"oops\"}"),
				normalizer.normalizeLine("data: {\"id\":\"x\",\"usage\":{},\"choices\":\"oops\"}"),
				"a non array choices field is relayed"
		);
	}

	private static String usageChunk(long prompt, long completion) {
		return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
				+ "\"model\":\"gpt-5.6-luna\",\"choices\":[],"
				+ "\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion
				+ ",\"total_tokens\":" + (prompt + completion) + "}}";
	}
}