package io.github.kxng0109.aegisgate.security.ratelimit;

/**
 * Fail-closed signal raised when the distributed rate-limit backend (Redis) is
 * unreachable or returns an unusable result. Propagated by {@link RateLimitEngine}
 * so the caller can reject the request instead of allowing it through.
 */
public class RateLimitUnavailableException extends RuntimeException {

	public RateLimitUnavailableException(String message) {
		super(message);
	}

	public RateLimitUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
