package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.servlet.ServletOutputStream;

import java.io.IOException;

/**
 * Periodic flush strategy for SSE relay connections.
 *
 * <p>The streaming virtual thread reports every line it wrote through {@link #onWrite(ServletOutputStream, int)}; the
 * strategy decides when the buffered bytes must be pushed to the downstream client using the adaptive dual trigger
 * (line count or elapsed time, whichever comes first) and executes the flush itself. {@link #onTimerTick()} is the
 * shared scheduler's registry scan: it never touches the output stream (the servlet output buffer is not thread-safe)
 * but marks connections that are due and tracks the flush lag used by the health indicator. {@link #register} and
 * {@link #unregister} keep the per-connection registry and the connection-limit gate in sync; a stream that reports
 * {@code true} from {@link #onWrite} must be torn down (the caller cancels the upstream exchange).</p>
 */
public interface SseFlushStrategy {

	/**
	 * Reports one completed line write and triggers a flush when a threshold is met.
	 *
	 * @param out        the registered downstream output stream the line was written to
	 * @param lineLength number of bytes written for this line, including the line terminator
	 * @return {@code true} when the stream must be aborted (downstream backpressure or buffer overflow), {@code false}
	 * to keep streaming
	 * @throws IOException when the output stream cannot be flushed and the strategy is in pass-through mode
	 */
	boolean onWrite(ServletOutputStream out, int lineLength) throws IOException;

	/**
	 * Scans the connection registry for streams whose time trigger elapsed.
	 *
	 * <p>Invoked periodically by the shared scheduler on its own thread. The method only observes registry state and
	 * marks due connections; the actual {@code flush()} always runs on the owning streaming virtual thread, never here,
	 * because Tomcat's output buffer is not thread-safe.</p>
	 */
	void onTimerTick();

	/**
	 * Registers a downstream stream with the flush engine and enforces the connection limit.
	 *
	 * @param out the servlet output stream of a relay connection
	 * @return a handle for {@link #unregister(SseFlushStrategy.FlushHandle)}
	 * @throws SseConnectionLimitException when the concurrent connection ceiling is reached
	 */
	FlushHandle register(ServletOutputStream out);

	/**
	 * Releases a registered connection, stops its watchdog, and records its lifetime.
	 *
	 * @param handle the handle returned by {@link #register(ServletOutputStream)}
	 */
	void unregister(FlushHandle handle);

	/**
	 * Atomically swaps the active configuration, applying hot reloads to live connections.
	 *
	 * @param props the new configuration snapshot
	 */
	void updateConfig(SseFlushProperties props);

	/**
	 * Opaque per-connection identity handed back by {@link SseFlushStrategy#register(ServletOutputStream)}.
	 */
	interface FlushHandle {

		/**
		 * @return the unique connection id assigned by the strategy
		 */
		long connectionId();

		/**
		 * @return the registered output stream
		 */
		ServletOutputStream outputStream();
	}
}