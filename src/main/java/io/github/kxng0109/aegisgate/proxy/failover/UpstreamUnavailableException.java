package io.github.kxng0109.aegisgate.proxy.failover;

import org.jspecify.annotations.Nullable;

/**
 * Signals that no provider could serve a request, or that a provider rejected
 * the request in a way that must be surfaced to the client as is.
 *
 * <p>Carries just enough structure for the error mapping layer to choose the
 * right HTTP status without leaking internal details:</p>
 * <ul>
 *   <li>a non zero {@code upstreamStatus} means a specific provider rejected
 *       the request (for example a 401 or 403 that must not be failed over)
 *       and that status should reach the client;</li>
 *   <li>{@code timedOut} means at least one attempt ran out of time and the
 *       client should see 504;</li>
 *   <li>{@code serviceUnavailable} means nothing usable was reachable (all
 *       circuits open, nothing configured, or a blocked target) and the client
 *       should see 503;</li>
 *   <li>otherwise the client should see 502.</li>
 * </ul>
 */
public class UpstreamUnavailableException extends RuntimeException {

	private final int upstreamStatus;
	private final boolean timedOut;
	private final boolean serviceUnavailable;

	/**
	 * Creates an exception with no specific upstream status.
	 *
	 * @param message            generic message describing the failure
	 * @param cause              the underlying cause, may be {@code null}
	 * @param serviceUnavailable whether the client should receive 503
	 * @param timedOut           whether the client should receive 504
	 */
	public UpstreamUnavailableException(
			String message,
			@Nullable Throwable cause,
			boolean serviceUnavailable,
			boolean timedOut
	) {
		this(message, cause, serviceUnavailable, timedOut, 0);
	}

	/**
	 * Creates an exception carrying a specific upstream HTTP status.
	 *
	 * @param message            generic message describing the failure
	 * @param cause              the underlying cause, may be {@code null}
	 * @param serviceUnavailable whether the client should receive 503
	 * @param timedOut           whether the client should receive 504
	 * @param upstreamStatus     the upstream status to surface to the client,
	 *                           or {@code 0} when there is none
	 */
	public UpstreamUnavailableException(
			String message,
			@Nullable Throwable cause,
			boolean serviceUnavailable,
			boolean timedOut,
			int upstreamStatus
	) {
		super(message, cause);
		this.serviceUnavailable = serviceUnavailable;
		this.timedOut = timedOut;
		this.upstreamStatus = upstreamStatus;
	}

	/**
	 * @return the upstream status to surface, or {@code 0} when absent
	 */
	public int getUpstreamStatus() {
		return upstreamStatus;
	}

	/**
	 * @return {@code true} when the client should receive 504 Gateway Timeout
	 */
	public boolean isTimedOut() {
		return timedOut;
	}

	/**
	 * @return {@code true} when the client should receive 503 Service Unavailable
	 */
	public boolean isServiceUnavailable() {
		return serviceUnavailable;
	}
}