package io.github.kxng0109.aegisgate.security.guardrail.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mid-stream kill switch implementing {@code TERMINATE_WITH_ERROR}.
 *
 * <p>Emits an OpenAI-compatible SSE error event downstream, cleanly forcing official client SDKs
 * (such as {@code openai-python} and {@code openai-node}) to raise an {@code APIError} without crashing deserializers.
 * Concurrently triggers an HTTP/2 {@code RST_STREAM(CANCEL)} frame upstream, halting GPU token inference and
 * terminating billing immediately.</p>
 */
public final class MidStreamKillSwitch {

	private static final Logger log = LoggerFactory.getLogger(MidStreamKillSwitch.class);

	private static final String DEFAULT_TERMINATION_EVENT =
			"event: error\n"
					+ "data: {\"error\":{\"message\":\"Stream terminated by AegisGate guardrail: content policy violation\",\"type\":\"guardrail_violation\",\"code\":\"content_filter\",\"param\":null}}\n\n";

	private MidStreamKillSwitch() {
	}

	/**
	 * Forcefully terminates both downstream and upstream streaming connections.
	 *
	 * @param out            downstream client OutputStream
	 * @param upstreamStream closeable upstream lines stream (closing cancels Flow.Subscription)
	 * @param reason         diagnostic reason for logging
	 */
	public static void terminate(OutputStream out, AutoCloseable upstreamStream, String reason) {
		log.warn("Executing mid-stream guardrail kill switch: reason={}", reason);

		// 1. Emit downstream wire event before closing socket
		if (out != null) {
			try {
				out.write(DEFAULT_TERMINATION_EVENT.getBytes(StandardCharsets.UTF_8));
				out.flush();
			} catch (IOException ex) {
				log.debug("Downstream client disconnected prior to kill switch event dispatch: {}", ex.getMessage());
			}
		}

		// 2. Upstream cancellation: triggers Flow.Subscription.cancel() -> HTTP/2 RST_STREAM(CANCEL)
		if (upstreamStream != null) {
			try {
				upstreamStream.close();
			} catch (Exception ex) {
				log.warn("Error closing upstream stream during kill switch invocation: {}", ex.getMessage());
			}
		}
	}
}
