package io.github.kxng0109.aegisgate.contracts;

import java.time.Instant;

/**
 * Snapshot of a key's rate-limit state after a check, used to populate the
 * {@code X-RateLimit-*} response headers.
 *
 * @param rpmLimit     configured requests-per-minute limit (0 = unlimited)
 * @param rpmRemaining remaining requests in the current window
 * @param rpmResetAt   instant the RPM window resets
 * @param tpmLimit     configured tokens-per-minute limit (0 = unlimited)
 * @param tpmRemaining remaining tokens in the current window
 * @param tpmResetAt   instant the TPM window resets
 */
public record RateLimitState(
		int rpmLimit,
		int rpmRemaining,
		Instant rpmResetAt,
		int tpmLimit,
		int tpmRemaining,
		Instant tpmResetAt
) {
}
