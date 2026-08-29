package io.github.kxng0109.aegisgate.security.filter;

import java.io.IOException;

/**
 * Signals that a request body exceeds the configured buffering limit.
 *
 * <p>Thrown by {@link CachedBodyHttpServletRequest} when the declared or
 * streamed content length exceeds its cap. The mapping layer converts this
 * into an HTTP {@code 413 Payload Too Large} response.</p>
 */
public final class BodyTooLargeException extends IOException {

	private final int limit;

	/**
	 * @param limit the configured body-size cap that was exceeded
	 */
	public BodyTooLargeException(int limit) {
		super("request body exceeds the configured limit of " + limit + " bytes");
		this.limit = limit;
	}

	/**
	 * @return the configured body-size cap that was exceeded
	 */
	public int getLimit() {
		return limit;
	}
}