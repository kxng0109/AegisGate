package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Protocol Normalizers Adversarial & Edge Case Test Suite")
class ProtocolNormalizerAdversarialTest {

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
	}

	@Nested
	@DisplayName("Malformed & Resilient SSE Handling")
	class MalformedSseTests {

		@ParameterizedTest(name = "[{index}] malformed input: {0}")
		@ValueSource(strings = {
				"data: {broken json",
				"data: {\"model\":",
				"data: [INVALID_JSON]",
				"event: message_delta\ndata: not-json",
				"   ",
				": keep-alive comment"
		})
		@DisplayName("Normalizers ignore malformed and unparseable lines cleanly")
		void shouldIgnoreMalformedSseLines(String malformedLine) {
			AnthropicSseNormalizer anthropic = new AnthropicSseNormalizer(objectMapper, "claude-3-5", true);
			OllamaSseNormalizer ollama = new OllamaSseNormalizer(objectMapper, "llama3.2", true);
			DeepSeekSseNormalizer deepseek = new DeepSeekSseNormalizer(objectMapper, "deepseek-r1", true);

			assertThat(anthropic.normalizeLine(malformedLine)).isEmpty();
			assertThat(ollama.normalizeLine(malformedLine)).isEmpty();
			assertThat(deepseek.normalizeLine(malformedLine)).isEmpty();
			assertThat(anthropic.isDone()).isFalse();
			assertThat(ollama.isDone()).isFalse();
			assertThat(deepseek.isDone()).isFalse();
		}

		@Test
		@DisplayName("Should correctly reconstruct split multibyte UTF-8 characters across lines")
		void shouldHandleSplitMultibyteUtf8Characters() {
			AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-5", true);

			List<String> lines1 = normalizer.normalizeLine("event: content_block_delta");
			assertThat(lines1).isEmpty();

			List<String> lines2 = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello 🔥\"}}");
			assertThat(lines2).hasSize(1);
			assertThat(lines2.getFirst()).contains("Hello 🔥");

			List<String> lines3 = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" 🚀 World\"}}");
			assertThat(lines3).hasSize(1);
			assertThat(lines3.getFirst()).contains(" 🚀 World");
		}

		@Test
		@DisplayName("Stream terminating without [DONE] preserves observed usage")
		void shouldHandleAbruptStreamCloseWithoutDone() {
			AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-5", true);

			normalizer.normalizeLine("event: message_start");
			normalizer.normalizeLine(
					"data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-5-sonnet\",\"usage\":{\"input_tokens\":50,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":10}}}");
			normalizer.normalizeLine("event: content_block_delta");
			normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Chunk 1\"}}");

			assertThat(normalizer.isDone()).isFalse();
			assertThat(normalizer.upstreamModel()).isEqualTo("claude-3-5-sonnet");
			assertThat(normalizer.cacheReadInputTokens()).isEqualTo(20L);
			assertThat(normalizer.cacheCreationInputTokens()).isEqualTo(10L);
		}
	}

	@Nested
	@DisplayName("Anthropic Claude 3.5/3.7 Thinking & Tool Calling Tests")
	class AnthropicSpecializedTests {

		@Test
		@DisplayName("Thinking deltas are converted to OpenAI reasoning_content chunks")
		void shouldConvertThinkingDeltasToReasoningContent() {
			AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-7-sonnet", true);

			normalizer.normalizeLine("event: content_block_delta");
			List<String> lines = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Analyzing user prompt...\"}}"
			);

			assertThat(lines).hasSize(1);
			assertThat(lines.getFirst())
					.contains("\"reasoning_content\":\"Analyzing user prompt...\"")
					.doesNotContain("\"content\":");
		}

		@Test
		@DisplayName("Tool calling stream correctly formats header and incremental argument deltas")
		void shouldNormalizeToolCallingStream() {
			AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-7-sonnet", true);

			// 1. Tool block start
			normalizer.normalizeLine("event: content_block_start");
			List<String> headerLines = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_abc123\",\"name\":\"execute_query\"}}"
			);
			assertThat(headerLines).hasSize(1);
			assertThat(headerLines.getFirst())
					.contains("\"name\":\"execute_query\"")
					.contains("\"id\":\"call_abc123\"");

			// 2. Incremental JSON arg deltas
			normalizer.normalizeLine("event: content_block_delta");
			List<String> deltaLines1 = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"sql\\\":\\\"\"}}"
			);
			assertThat(deltaLines1).hasSize(1);
			assertThat(deltaLines1.getFirst()).contains("{\\\"sql\\\":\\\"");

			normalizer.normalizeLine("event: content_block_delta");
			List<String> deltaLines2 = normalizer.normalizeLine(
					"data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"SELECT 1\\\"}\"}}"
			);
			assertThat(deltaLines2).hasSize(1);
			assertThat(deltaLines2.getFirst()).contains("SELECT 1\\\"}\"");

			// 3. Message stop with thinking telemetry
			normalizer.normalizeLine("event: message_delta");
			normalizer.normalizeLine(
					"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20,\"output_tokens_details\":{\"thinking_tokens\":12}}}");
			normalizer.normalizeLine("event: message_stop");
			List<String> terminalLines = normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

			assertThat(terminalLines)
					.hasSize(3)
					.satisfies(list -> {
						assertThat(list.get(0)).contains("\"usage\":").contains("\"reasoning_tokens\":12");
						assertThat(list.get(1)).contains("\"finish_reason\":\"tool_calls\"");
						assertThat(list.get(2)).isEqualTo("data: [DONE]");
					});
			assertThat(normalizer.isDone()).isTrue();
			assertThat(normalizer.reasoningTokens()).isEqualTo(12L);
		}

		@ParameterizedTest(name = "[{index}] Anthropic stop_reason={0} maps to finish_reason={1}")
		@CsvSource({
				"end_turn, stop",
				"stop_sequence, stop",
				"max_tokens, length",
				"tool_use, tool_calls",
				"unknown_reason, stop"
		})
		@DisplayName("Anthropic stop reasons map deterministically to OpenAI finish reasons")
		void shouldMapStopReasonsCorrectly(String anthropicReason, String expectedFinishReason) {
			AnthropicSseNormalizer normalizer = new AnthropicSseNormalizer(objectMapper, "claude-3-5", false);
			normalizer.normalizeLine("event: message_delta");
			normalizer.normalizeLine(
					"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + anthropicReason + "\"}}");
			normalizer.normalizeLine("event: message_stop");
			List<String> terminal = normalizer.normalizeLine("data: {\"type\":\"message_stop\"}");

			assertThat(terminal).anyMatch(line -> line.contains("\"finish_reason\":\"" + expectedFinishReason + "\""));
		}
	}

	@Nested
	@DisplayName("Ollama NDJSON Edge Cases")
	class OllamaSpecializedTests {

		@Test
		@DisplayName("Ollama unary non-streaming line emits delta, usage, finish and DONE")
		void shouldHandleUnaryDoneLine() {
			OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "llama3.2", true);

			String unaryLine = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"Complete answer\"},\"done\":true,\"prompt_eval_count\":15,\"eval_count\":40}";
			List<String> lines = normalizer.normalizeLine(unaryLine);

			assertThat(lines).hasSize(4);
			assertThat(lines.get(0)).contains("\"content\":\"Complete answer\"");
			assertThat(lines.get(1)).contains("\"prompt_tokens\":15").contains("\"completion_tokens\":40");
			assertThat(lines.get(2)).contains("\"finish_reason\":\"stop\"");
			assertThat(lines.get(3)).isEqualTo("data: [DONE]");
			assertThat(normalizer.isDone()).isTrue();
		}

		@Test
		@DisplayName("Ollama with native thinking field and tool calls streams properly")
		void shouldHandleThinkingAndToolCalls() {
			OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "deepseek-r1:7b", false);

			String line1 = "{\"model\":\"deepseek-r1:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"Solving step 1\"},\"done\":false}";
			List<String> r1 = normalizer.normalizeLine(line1);
			assertThat(r1).hasSize(1);
			assertThat(r1.getFirst()).contains("\"reasoning_content\":\"Solving step 1\"");

			// Tool call with string argument and embedded think tags in content
			String line2 = "{\"model\":\"deepseek-r1:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"<think>step 2</think>result\",\"tool_calls\":[{\"function\":{\"name\":\"calc\",\"arguments\":\"{\\\"expr\\\":\\\"2+2\\\"}\"}}]},\"done\":false}";
			List<String> r2 = normalizer.normalizeLine(line2);
			assertThat(r2).hasSize(3); // reasoning delta, content delta, tool call header

			// Flushed reasoning from partial close tag at end of stream on an open thinking stream
			OllamaSseNormalizer nThinking = new OllamaSseNormalizer(objectMapper, "deepseek-r1:7b", false);
			nThinking.normalizeLine(
					"{\"model\":\"deepseek-r1:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"<think>step 1 \"},\"done\":false}");
			String line3 = "{\"model\":\"deepseek-r1:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"</th\"},\"done\":true,\"done_reason\":\"length\"}";
			List<String> r3 = nThinking.normalizeLine(line3);
			assertThat(r3).hasSize(3); // flushed reasoning, finish chunk, done
			assertThat(r3.get(0)).contains("\"reasoning_content\":\"</th\"");
			assertThat(r3.get(1)).contains("\"finish_reason\":\"length\"");
			assertThat(r3.get(2)).isEqualTo("data: [DONE]");

			// Normalizer with flushed content outside thinking
			OllamaSseNormalizer n2 = new OllamaSseNormalizer(objectMapper, "llama3.2", false);
			n2.normalizeLine(
					"{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"response <t\"},\"done\":true}");
			assertThat(n2.isDone()).isTrue();
		}

		@Test
		@DisplayName("DeepSeekSseNormalizer exposes cached and reasoning token accessors")
		void shouldTestDeepSeekAccessors() {
			DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "deepseek-r1", true);
			normalizer.normalizeLine(
					"data: {\"model\":\"deepseek-r1\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"prompt_cache_hit_tokens\":60,\"completion_tokens_details\":{\"reasoning_tokens\":25}}}");
			normalizer.normalizeLine("data: [DONE]");

			assertThat(normalizer.cachedTokens()).isEqualTo(60L);
			assertThat(normalizer.reasoningTokens()).isEqualTo(25L);
			assertThat(normalizer.usage()).isNotNull();
			assertThat(normalizer.isDone()).isTrue();

			// DeepSeek with includeUsageInResponse = false suppresses standalone usage chunk
			DeepSeekSseNormalizer nNoUsage = new DeepSeekSseNormalizer(objectMapper, "deepseek-r1", false);
			List<String> suppressed = nNoUsage.normalizeLine(
					"data: {\"model\":\"deepseek-r1\",\"usage\":{\"prompt_tokens\":100}}");
			assertThat(suppressed).isEmpty();
			assertThat(nNoUsage.usage()).isNotNull();
			assertThat(nNoUsage.usage().promptTokens()).isEqualTo(100L);
			assertThat(nNoUsage.usage().completionTokens()).isEqualTo(0L);

			// DeepSeek with only completion tokens
			DeepSeekSseNormalizer nCompOnly = new DeepSeekSseNormalizer(objectMapper, "deepseek-r1", false);
			nCompOnly.normalizeLine("data: {\"model\":\"deepseek-r1\",\"usage\":{\"completion_tokens\":50}}");
			assertThat(nCompOnly.usage()).isNotNull();
			assertThat(nCompOnly.usage().promptTokens()).isEqualTo(0L);
			assertThat(nCompOnly.usage().completionTokens()).isEqualTo(50L);
		}

		@Test
		@DisplayName("Ollama missing eval_count defaults token counts to zero without failing")
		void shouldHandleMissingEvalCount() {
			OllamaSseNormalizer normalizer = new OllamaSseNormalizer(objectMapper, "llama3.2", true);

			String doneWithoutTokens = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}";
			List<String> lines = normalizer.normalizeLine(doneWithoutTokens);

			assertThat(lines).hasSize(3);
			assertThat(normalizer.usage()).isNotNull();
			assertThat(normalizer.usage().promptTokens()).isEqualTo(0L);
			assertThat(normalizer.usage().completionTokens()).isEqualTo(0L);
			assertThat(normalizer.isDone()).isTrue();
		}
	}
}
