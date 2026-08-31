package io.github.kxng0109.aegisgate.proxy.sse;

/**
 * Exception thrown when an SSE line exceeds the configured maximum byte length. Carries the configured limit and the
 * actual observed byte count for diagnostics.
 */
public final class LineTooLongException extends RuntimeException {

	private final int limitBytes;
	private final int actualBytes;
	private final String provider;

	/**
	 * Creates a new line-too-long exception.
	 *
	 * @param limitBytes  the configured maximum line length in bytes
	 * @param actualBytes the actual byte length observed
	 * @param provider    the upstream provider name
	 */
	public LineTooLongException(int limitBytes, int actualBytes, String provider) {
		super("SSE line exceeds configured maximum of %d bytes (actual: %d, provider: %s)"
				      .formatted(limitBytes, actualBytes, provider));
		this.limitBytes = limitBytes;
		this.actualBytes = actualBytes;
		this.provider = provider;
	}

	/**
	 * @return the configured maximum line length in bytes
	 */
	public int limitBytes() {
		return limitBytes;
	}

	/**
	 * @return the actual byte length that triggered the violation
	 */
	public int actualBytes() {
		return actualBytes;
	}

	/**
	 * @return the upstream provider name
	 */
	public String provider() {
		return provider;
	}
}