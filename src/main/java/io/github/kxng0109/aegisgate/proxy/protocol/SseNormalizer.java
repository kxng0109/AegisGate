package io.github.kxng0109.aegisgate.proxy.protocol;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Turns one provider's streaming wire lines into OpenAI shaped SSE lines.
 *
 * <p>Each provider speaks a different dialect: OpenAI emits SSE lines that
 * already match the client contract, Anthropic emits typed SSE events across two lines (an {@code event:} line followed
 * by a {@code data:} line), and Ollama emits newline delimited JSON. A normalizer hides all of that behind a single
 * line at a time contract, so the relay loop stays zero buffered: every input line is consumed, zero or more output
 * lines are produced, and nothing is accumulated beyond one line.</p>
 *
 * <p>A fresh instance is created per streamed response because a normalizer
 * carries stream state: the pending Anthropic event name, the accumulated usage, and the terminal flag.</p>
 */
public sealed interface SseNormalizer permits OpenAiSseNormalizer,
                                              AnthropicSseNormalizer,
                                              OllamaSseNormalizer,
                                              GeminiSseNormalizer,
                                              DeepSeekSseNormalizer {

	/**
	 * Consumes one raw line from the upstream and returns the lines to write to the client. The result may be empty
	 * (keep alive events, dropped reasoning deltas) or contain several lines (a terminal event that emits the final
	 * chunk, an optional usage chunk, and the {@code [DONE]} marker).
	 *
	 * @param rawLine one upstream line, without the trailing newline
	 * @return the OpenAI shaped lines to relay, never {@code null}
	 */
	List<String> normalizeLine(String rawLine);

	/**
	 * @return {@code true} once the stream has reached its terminal event
	 */
	boolean isDone();

	/**
	 * The token usage observed so far, {@code null} until the stream finished and the provider reported counts.
	 *
	 * @return the accumulated usage, or {@code null}
	 */
	@Nullable UsageInfo usage();

	/**
	 * The model id reported by the provider in the stream, or the requested model when the provider never stated one.
	 *
	 * @return the model id to attribute cost against
	 */
	@Nullable String upstreamModel();

	/**
	 * Token counts observed on the wire.
	 *
	 * @param promptTokens     input tokens billed by the provider
	 * @param completionTokens output tokens billed by the provider
	 */
	record UsageInfo(long promptTokens, long completionTokens) {
	}
}