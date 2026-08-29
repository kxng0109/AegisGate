package io.github.kxng0109.aegisgate.contracts;

/**
 * Result of a rate-limit check. Use pattern matching (switch on the sealed
 * type) to branch on {@code Allowed} vs {@code Rejected}.
 */
public sealed interface RateLimitDecision {

	/**
	 * The request is within all limits.
	 *
	 * @param state the post-check rate-limit state for response headers
	 */
	record Allowed(RateLimitState state) implements RateLimitDecision {
	}

	/**
	 * The request exceeded a limit and must be rejected.
	 *
	 * @param reason            why it was rejected
	 * @param retryAfterSeconds seconds the client should wait before retrying
	 */
	record Rejected(RejectionReason reason, long retryAfterSeconds) implements RateLimitDecision {
	}
}
