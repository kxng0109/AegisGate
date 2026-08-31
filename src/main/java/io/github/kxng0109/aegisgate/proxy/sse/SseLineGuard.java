package io.github.kxng0109.aegisgate.proxy.sse;

import java.util.List;
import java.util.Map;

/**
 * Per-stream guard that validates upstream SSE lines against configurable byte/line limits and decides whether to
 * relay, drop, or terminate the stream, protecting downstream handlers from oversized or abusive payloads.
 */
public interface SseLineGuard {

	/**
	 * Validates a single upstream line against the configured limits.
	 *
	 * @param line     the raw upstream line (without trailing newline)
	 * @param provider the upstream provider type
	 * @return the lines to relay downstream; empty means drop the line; non-empty with {@link #isRejected()} true means
	 * the stream must be terminated after writing the returned lines (SSE error event)
	 */
	List<String> checkLine(String line, ProviderType provider);

	/**
	 * @return true if the last {@link #checkLine} call triggered a rejection ({@link Action#REJECT_LINE_AND_CLOSE})
	 */
	boolean isRejected();

	/**
	 * Called when the stream completes normally (reached end-of-stream or normalizer DONE).
	 */
	void onStreamComplete();

	/**
	 * Called when the stream is aborted (client disconnect, write timeout, upstream error, or line rejection).
	 *
	 * @param reason a machine-readable reason code (e.g., "client_disconnect", "write_timeout", "line_too_long",
	 *               "upstream_error")
	 */
	void onStreamAbort(String reason);

	/**
	 * Returns a snapshot of the current configuration (hot-reloadable).
	 */
	ConfigSnapshot config();

	/**
	 * Enumeration of possible actions when a line is rejected.
	 */
	enum Action {
		/**
		 * Reject the line, emit an SSE error event, and close the stream.
		 */
		REJECT_LINE_AND_CLOSE,
		/**
		 * Reject the line (drop it) but continue streaming.
		 */
		REJECT_LINE_CONTINUE
	}

	/**
	 * Supported upstream provider types for line-limit configuration.
	 */
	enum ProviderType {
		OPENAI,
		ANTHROPIC,
		OLLAMA,
		UNKNOWN;

		/**
		 * Converts from the contracts ProviderType to the guard's ProviderType.
		 *
		 * @param type the contracts provider type
		 * @return the corresponding guard provider type
		 */
		public static ProviderType from(io.github.kxng0109.aegisgate.contracts.ProviderType type) {
			if (type == null) {
				return UNKNOWN;
			}
			return switch (type) {
				case OPENAI -> OPENAI;
				case ANTHROPIC -> ANTHROPIC;
				case OLLAMA -> OLLAMA;
				default -> UNKNOWN;
			};
		}
	}

	/**
	 * Per-provider line-limit configuration.
	 *
	 * @param maxLineBytes      maximum line length in bytes for this provider
	 * @param maxLinesPerSecond maximum lines per second (rate limit)
	 * @param maxBytesPerSecond maximum bytes per second (rate limit)
	 */
	public record ProviderConfig(int maxLineBytes, int maxLinesPerSecond, int maxBytesPerSecond) {
		public ProviderConfig {
			if (maxLineBytes <= 0) {
				throw new IllegalArgumentException("maxLineBytes must be positive");
			}
			if (maxLinesPerSecond <= 0) {
				throw new IllegalArgumentException("maxLinesPerSecond must be positive");
			}
			if (maxBytesPerSecond <= 0) {
				throw new IllegalArgumentException("maxBytesPerSecond must be positive");
			}
		}
	}

	/**
	 * Snapshot of the guard's current configuration (hot-reloadable).
	 *
	 * @param globalDefaultBytes  default maximum line length in bytes
	 * @param perProvider         per-provider overrides
	 * @param safetyMarginPercent safety margin percent applied to body handler limit
	 * @param action              action on line rejection
	 */
	record ConfigSnapshot(
			int globalDefaultBytes,
			Map<ProviderType, ProviderConfig> perProvider,
			int safetyMarginPercent,
			Action action
	) {
	}
}