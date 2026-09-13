package io.github.kxng0109.aegisgate.notify;

/**
 * Outcome of one channel send attempt.
 */
public enum ChannelResult {

	/** Delivered (2xx). */
	SENT,
	/** Channel not configured (email without Graph credentials, etc.): logged, never retried blindly. */
	SKIPPED,
	/** Throttled or 5xx/timeout: safe to retry with backoff. */
	TRANSIENT,
	/** Rejected, bounced, or misconfigured: must not be retried without operator action. */
	TERMINAL
}
