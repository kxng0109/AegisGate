package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfViolationException;

import java.net.URI;

/**
 * Validates an upstream URL before the gateway connects to it.
 *
 * <p>The production implementation delegates to the SSRF control; tests and
 * in process mock servers use a permissive implementation. Validation is deliberately testable through this seam so
 * that no test is forced onto the real network or the blocked loopback ranges.</p>
 */
@FunctionalInterface
public interface UpstreamUrlValidator {

	/**
	 * Validates a target URL, throwing when the target must not be contacted.
	 *
	 * @param targetUrl the URL about to be contacted
	 * @throws SsrfViolationException when the target is unsafe (or unresolvable)
	 */
	void validate(URI targetUrl);

	/**
	 * Whether the given host is explicitly trusted to resolve to a private address (local development backends such as
	 * LM Studio, Ollama, vLLM). The default denies; {@link SsrfUpstreamUrlValidator} overrides this from configuration.
	 * Non-default implementations are never consulted by the failover path, so test lambdas stay unaffected.
	 *
	 * @param host the target host
	 * @return true if private resolution is permitted for this host
	 */
	default boolean isPrivateHostAllowed(String host) {
		return false;
	}
}