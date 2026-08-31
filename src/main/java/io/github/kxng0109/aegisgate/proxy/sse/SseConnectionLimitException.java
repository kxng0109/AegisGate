package io.github.kxng0109.aegisgate.proxy.sse;

/**
 * Thrown when the global concurrent SSE stream ceiling is reached.
 *
 * <p>The ceiling is a hard, last-resort resource guard ({@link AdaptiveSseFlushStrategy#MAX_CONNECTIONS}); the caller
 * decides how to shed the rejected stream (the proxy logs a warning and ends the stream before any upstream bytes are
 * consumed).</p>
 */
public class SseConnectionLimitException extends RuntimeException {

	/**
	 * Creates the exception with the given message.
	 *
	 * @param message human readable reason
	 */
	public SseConnectionLimitException(String message) {
		super(message);
	}
}