package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiSseLine Unit Tests")
@SuppressWarnings("DataFlowIssue")
class OpenAiSseLineTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("delta constructs valid OpenAI chat completion chunk")
	void testDelta() throws Exception {
		String line = OpenAiSseLine.delta(mapper, "chatcmpl-123", 1700000000L, "gpt-4o", "Hello world");
		assertTrue(line.startsWith("data: "));
		JsonNode node = mapper.readTree(line.substring(6));

		assertEquals("chatcmpl-123", node.get("id").asString());
		assertEquals("chat.completion.chunk", node.get("object").asString());
		assertEquals(1700000000L, node.get("created").asLong());
		assertEquals("gpt-4o", node.get("model").asString());
		assertEquals(0, node.get("choices").get(0).get("index").asInt());
		assertEquals("Hello world", node.get("choices").get(0).get("delta").get("content").asString());
	}

	@Test
	@DisplayName("reasoningDelta constructs valid reasoning chunk")
	void testReasoningDelta() throws Exception {
		String line = OpenAiSseLine.reasoningDelta(
				mapper,
				"chatcmpl-123",
				1700000000L,
				"deepseek-r1",
				"Let's think step by step"
		);
		assertTrue(line.startsWith("data: "));
		JsonNode node = mapper.readTree(line.substring(6));

		assertEquals("chatcmpl-123", node.get("id").asString());
		assertEquals("deepseek-r1", node.get("model").asString());
		assertEquals(
				"Let's think step by step",
				node.get("choices").get(0).get("delta").get("reasoning_content").asString()
		);
	}

	@Test
	@DisplayName("toolCallHeader constructs valid tool declaration with ID and initial arguments")
	void testToolCallHeaderWithIdAndArgs() throws Exception {
		String line = OpenAiSseLine.toolCallHeader(
				mapper, "chatcmpl-123", 1700000000L, "gpt-4o",
				0, "call_abc123", "get_weather", "{\"loc\":"
		);
		assertTrue(line.startsWith("data: "));
		JsonNode node = mapper.readTree(line.substring(6));

		JsonNode toolCall = node.get("choices").get(0).get("delta").get("tool_calls").get(0);
		assertEquals(0, toolCall.get("index").asInt());
		assertEquals("call_abc123", toolCall.get("id").asString());
		assertEquals("function", toolCall.get("type").asString());
		assertEquals("get_weather", toolCall.get("function").get("name").asString());
		assertEquals("{\"loc\":", toolCall.get("function").get("arguments").asString());
	}

	@Test
	@DisplayName("toolCallHeader constructs valid tool declaration with null/blank ID and null arguments")
	void testToolCallHeaderNullIdAndArgs() throws Exception {
		String line = OpenAiSseLine.toolCallHeader(
				mapper, "chatcmpl-123", 1700000000L, "gpt-4o",
				1, "   ", "calculate", null
		);
		JsonNode node = mapper.readTree(line.substring(6));

		JsonNode toolCall = node.get("choices").get(0).get("delta").get("tool_calls").get(0);
		assertEquals(1, toolCall.get("index").asInt());
		assertNull(toolCall.get("id"));
		assertEquals("function", toolCall.get("type").asString());
		assertEquals("calculate", toolCall.get("function").get("name").asString());
		assertEquals("", toolCall.get("function").get("arguments").asString());

		// Test null ID
		String lineNull = OpenAiSseLine.toolCallHeader(
				mapper, "chatcmpl-123", 1700000000L, "gpt-4o",
				1, null, "calculate", ""
		);
		JsonNode nodeNull = mapper.readTree(lineNull.substring(6));
		assertNull(nodeNull.get("choices").get(0).get("delta").get("tool_calls").get(0).get("id"));
	}

	@Test
	@DisplayName("toolCallArgumentDelta constructs valid incremental argument chunk")
	void testToolCallArgumentDelta() throws Exception {
		String line = OpenAiSseLine.toolCallArgumentDelta(
				mapper, "chatcmpl-123", 1700000000L, "gpt-4o",
				0, " \"Paris\"}"
		);
		JsonNode node = mapper.readTree(line.substring(6));

		JsonNode toolCall = node.get("choices").get(0).get("delta").get("tool_calls").get(0);
		assertEquals(0, toolCall.get("index").asInt());
		assertEquals(" \"Paris\"}", toolCall.get("function").get("arguments").asString());
		assertNull(toolCall.get("id"));
		assertNull(toolCall.get("type"));
	}

	@Test
	@DisplayName("finished and finishedWithReason handle various finish reasons")
	void testFinished() throws Exception {
		String defaultFinish = OpenAiSseLine.finished(mapper, "chatcmpl-123", 1700000000L, "gpt-4o");
		JsonNode node1 = mapper.readTree(defaultFinish.substring(6));
		assertEquals("stop", node1.get("choices").get(0).get("finish_reason").asString());

		String toolFinish = OpenAiSseLine.finishedWithReason(
				mapper,
				"chatcmpl-123",
				1700000000L,
				"gpt-4o",
				"tool_calls"
		);
		JsonNode node2 = mapper.readTree(toolFinish.substring(6));
		assertEquals("tool_calls", node2.get("choices").get(0).get("finish_reason").asString());

		String nullFinish = OpenAiSseLine.finishedWithReason(mapper, "chatcmpl-123", 1700000000L, "gpt-4o", null);
		JsonNode node3 = mapper.readTree(nullFinish.substring(6));
		assertEquals("stop", node3.get("choices").get(0).get("finish_reason").asString());
	}

	@Test
	@DisplayName("usage and usageWithDetails correctly format token metrics")
	void testUsage() throws Exception {
		String basicUsage = OpenAiSseLine.usage(mapper, "chatcmpl-123", 1700000000L, "gpt-4o", 100, 50);
		JsonNode node1 = mapper.readTree(basicUsage.substring(6));
		JsonNode usage1 = node1.get("usage");
		assertEquals(100, usage1.get("prompt_tokens").asLong());
		assertEquals(50, usage1.get("completion_tokens").asLong());
		assertEquals(150, usage1.get("total_tokens").asLong());
		assertNull(usage1.get("prompt_tokens_details"));
		assertNull(usage1.get("completion_tokens_details"));

		String detailedUsage = OpenAiSseLine.usageWithDetails(
				mapper, "chatcmpl-123", 1700000000L, "gpt-4o",
				100, 50, 40, 25
		);
		JsonNode node2 = mapper.readTree(detailedUsage.substring(6));
		JsonNode usage2 = node2.get("usage");
		assertEquals(100, usage2.get("prompt_tokens").asLong());
		assertEquals(50, usage2.get("completion_tokens").asLong());
		assertEquals(150, usage2.get("total_tokens").asLong());
		assertEquals(40, usage2.get("prompt_tokens_details").get("cached_tokens").asLong());
		assertEquals(25, usage2.get("completion_tokens_details").get("reasoning_tokens").asLong());
	}

	@Test
	@DisplayName("DONE constant is valid")
	void testDoneConstant() {
		assertEquals("data: [DONE]", OpenAiSseLine.DONE);
	}
}
