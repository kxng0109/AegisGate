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
}