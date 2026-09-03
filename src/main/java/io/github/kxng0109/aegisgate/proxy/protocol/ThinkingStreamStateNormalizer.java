package io.github.kxng0109.aegisgate.proxy.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful sliding-window streaming state machine that separates embedded {@code <think>...</think>} reasoning tags
 * across arbitrary chunk boundaries into distinct reasoning and content deltas.
 *
 * <p>Operates with an $O(1)$ sliding window carry buffer ($\le 8$ characters), ensuring zero heap accumulation
 * and zero full-response buffering during streaming.</p>
 */
public final class ThinkingStreamStateNormalizer {

	private static final String THINK_OPEN = "<think>";
	private static final String THINK_CLOSE = "</think>";

	/**
	 * Categorization of normalized stream fragments.
	 */
	public enum ChunkType {
		/**
		 * Reasoning / Chain-of-Thought tokens intended for {@code delta.reasoning_content}.
		 */
		REASONING,
		/**
		 * Canonical response content intended for {@code delta.content}.
		 */
		CONTENT
	}

	/**
	 * One classified fragment emitted by the state normalizer.
	 *
	 * @param type classification of the text
	 * @param text slice of text to stream
	 */
	public record NormalizedChunk(ChunkType type, String text) {
	}

	private enum State {
		INITIAL,
		INSIDE_THINKING,
		AFTER_THINKING
	}

	private State state = State.INITIAL;
	private final StringBuilder carryBuffer = new StringBuilder();

	/**
	 * Processes an incoming chunk fragment from the upstream provider.
	 *
	 * @param fragment raw incoming string slice
	 * @return ordered list of classified fragments to emit downstream
	 */
	public List<NormalizedChunk> process(String fragment) {
		if (fragment == null || fragment.isEmpty()) {
			return List.of();
		}

		List<NormalizedChunk> results = new ArrayList<>();
		carryBuffer.append(fragment);
		String text = carryBuffer.toString();
		carryBuffer.setLength(0);

		int cursor = 0;
		int len = text.length();

		while (cursor < len) {
			if (state == State.INITIAL) {
				int openIdx = text.indexOf(THINK_OPEN, cursor);
				if (openIdx == cursor) {
					state = State.INSIDE_THINKING;
					cursor += THINK_OPEN.length();
				} else if (openIdx > cursor) {
					// Leading text before <think>
					String lead = text.substring(cursor, openIdx);
					results.add(new NormalizedChunk(ChunkType.CONTENT, lead));
					state = State.INSIDE_THINKING;
					cursor = openIdx + THINK_OPEN.length();
				} else {
					// Check for partial <think> suffix at the end of text
					int partialLen = partialMatchLength(text, cursor, THINK_OPEN);
					if (partialLen > 0) {
						if (cursor < text.length() - partialLen) {
							results.add(new NormalizedChunk(
									ChunkType.CONTENT,
									text.substring(cursor, text.length() - partialLen)
							));
						}
						carryBuffer.append(text.substring(text.length() - partialLen));
						cursor = len;
					} else {
						// No think tag at all; transition to standard content streaming
						results.add(new NormalizedChunk(ChunkType.CONTENT, text.substring(cursor)));
						cursor = len;
					}
				}
			} else if (state == State.INSIDE_THINKING) {
				int closeIdx = text.indexOf(THINK_CLOSE, cursor);
				if (closeIdx >= 0) {
					if (closeIdx > cursor) {
						results.add(new NormalizedChunk(ChunkType.REASONING, text.substring(cursor, closeIdx)));
					}
					state = State.AFTER_THINKING;
					cursor = closeIdx + THINK_CLOSE.length();
					// Skip immediate single trailing newline after </think> if present
					if (cursor < len && text.charAt(cursor) == '\n') {
						cursor++;
					}
				} else {
					// Check for partial </think> suffix
					int partialLen = partialMatchLength(text, cursor, THINK_CLOSE);
					if (partialLen > 0) {
						if (cursor < text.length() - partialLen) {
							results.add(new NormalizedChunk(
									ChunkType.REASONING,
									text.substring(cursor, text.length() - partialLen)
							));
						}
						carryBuffer.append(text.substring(text.length() - partialLen));
						cursor = len;
					} else {
						results.add(new NormalizedChunk(ChunkType.REASONING, text.substring(cursor)));
						cursor = len;
					}
				}
			} else { // State.AFTER_THINKING
				results.add(new NormalizedChunk(ChunkType.CONTENT, text.substring(cursor)));
				cursor = len;
			}
		}

		return results;
	}

	/**
	 * Flushes any remaining characters in the carry buffer at end-of-stream.
	 *
	 * @return final pending chunks, if any
	 */
	public List<NormalizedChunk> flush() {
		if (carryBuffer.isEmpty()) {
			return List.of();
		}
		String remaining = carryBuffer.toString();
		carryBuffer.setLength(0);
		ChunkType type = (state == State.INSIDE_THINKING) ? ChunkType.REASONING : ChunkType.CONTENT;
		return List.of(new NormalizedChunk(type, remaining));
	}

	private static int partialMatchLength(String text, int fromIndex, String target) {
		int remaining = text.length() - fromIndex;
		int maxCheck = Math.min(remaining, target.length() - 1);
		for (int len = maxCheck; len > 0; len--) {
			if (text.endsWith(target.substring(0, len))) {
				return len;
			}
		}
		return 0;
	}
}
