package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ThinkingStreamStateNormalizer Sliding Window State Machine Tests")
class ThinkingStreamStateNormalizerTest {

	private ThinkingStreamStateNormalizer normalizer;

	@BeforeEach
	void setUp() {
		normalizer = new ThinkingStreamStateNormalizer();
	}

	@Test
	@DisplayName("Should extract thinking content and subsequent canonical content cleanly")
	void shouldExtractThinkingAndContent() {
		List<ThinkingStreamStateNormalizer.NormalizedChunk> c1 = normalizer.process(
				"<think>Let x be 10.</think>The answer is 10.");
		assertThat(c1).hasSize(2);
		assertThat(c1.get(0).type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.REASONING);
		assertThat(c1.get(0).text()).isEqualTo("Let x be 10.");
		assertThat(c1.get(1).type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(c1.get(1).text()).isEqualTo("The answer is 10.");
	}

	@Test
	@DisplayName("Should handle thinking tag split across chunk boundaries")
	void shouldHandleSplitOpeningTag() {
		List<ThinkingStreamStateNormalizer.NormalizedChunk> c1 = normalizer.process("<th");
		assertThat(c1).isEmpty();

		List<ThinkingStreamStateNormalizer.NormalizedChunk> c2 = normalizer.process("ink>Thinking step 1.");
		assertThat(c2).hasSize(1);
		assertThat(c2.getFirst().type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.REASONING);
		assertThat(c2.getFirst().text()).isEqualTo("Thinking step 1.");

		List<ThinkingStreamStateNormalizer.NormalizedChunk> c3 = normalizer.process("</th");
		assertThat(c3).isEmpty();

		List<ThinkingStreamStateNormalizer.NormalizedChunk> c4 = normalizer.process("ink>\nFinal response.");
		assertThat(c4).hasSize(1);
		assertThat(c4.getFirst().type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(c4.getFirst().text()).isEqualTo("Final response.");
	}

	@Test
	@DisplayName("Should handle leading text before open tag and empty thinking block")
	void shouldHandleLeadingTextAndEmptyThinking() {
		List<ThinkingStreamStateNormalizer.NormalizedChunk> c1 = normalizer.process(
				"Prefix <think></think>No newline suffix");
		assertThat(c1).hasSize(2);
		assertThat(c1.get(0).type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(c1.get(0).text()).isEqualTo("Prefix ");
		assertThat(c1.get(1).type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(c1.get(1).text()).isEqualTo("No newline suffix");
	}

	@Test
	@DisplayName("Should handle standalone partial tags and flush outside thinking")
	void shouldHandleStandalonePartialTagsAndFlush() {
		normalizer.process("Hello world <");
		List<ThinkingStreamStateNormalizer.NormalizedChunk> flushed = normalizer.flush();
		assertThat(flushed).hasSize(1);
		assertThat(flushed.getFirst().type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(flushed.getFirst().text()).isEqualTo("<");
	}

	@Test
	@DisplayName("Should handle exact partial tag boundaries during thinking mode")
	void shouldHandleExactPartialTagBoundariesInThinking() {
		normalizer.process("<think>thinking");
		// Send chunk that is ONLY a partial close tag
		List<ThinkingStreamStateNormalizer.NormalizedChunk> c1 = normalizer.process("</th");
		assertThat(c1).isEmpty();

		List<ThinkingStreamStateNormalizer.NormalizedChunk> c2 = normalizer.process("ink>all done");
		assertThat(c2).hasSize(1);
		assertThat(c2.getFirst().type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.CONTENT);
		assertThat(c2.getFirst().text()).isEqualTo("all done");
	}

	@Test
	@DisplayName("Should flush remaining carry buffer on abrupt stream close")
	void shouldFlushCarryBufferOnClose() {
		normalizer.process("<think>Incomplete reasoning <");
		List<ThinkingStreamStateNormalizer.NormalizedChunk> flushed = normalizer.flush();

		assertThat(flushed).hasSize(1);
		assertThat(flushed.getFirst().type()).isEqualTo(ThinkingStreamStateNormalizer.ChunkType.REASONING);
		assertThat(flushed.getFirst().text()).isEqualTo("<");

		assertThat(normalizer.flush()).isEmpty();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("Should gracefully handle null and empty input fragments")
	void shouldHandleNullAndEmptyFragments(String input) {
		assertThat(normalizer.process(input)).isEmpty();
	}
}
