package io.github.kxng0109.aegisgate.proxy;

import java.time.Duration;
import java.util.UUID;

/**
 * Result of a single proxy operation for logging and observability.
 *
 * <p>Captures the outcome of the upstream request including timing, byte counts,
 * terminal status, and optional error details.
 */
public record ProxyResult(
		UUID requestId,
		int upstreamStatusCode,
		long totalBytesProxied,
		Duration duration,
		ProxyResultStatus status,
		String errorDetail
) {

	/**
	 * Terminal status of the proxy operation.
	 */
	public enum ProxyResultStatus {
		/**
		 * Request completed successfully with upstream 2xx response.
		 */
		SUCCESS,
		/**
		 * Downstream client disconnected before stream completion.
		 */
		CLIENT_ABORT,
		/**
		 * Upstream returned an error status or connection failed.
		 */
		UPSTREAM_ERROR,
		/**
		 * Request blocked by SSRF validation.
		 */
		SSRF_BLOCKED
	}
}
