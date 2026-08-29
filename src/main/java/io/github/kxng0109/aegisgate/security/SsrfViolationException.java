package io.github.kxng0109.aegisgate.security;

import java.io.Serial;
import java.net.UnknownHostException;

/**
 * Thrown when a target URL is rejected by SSRF validation before any network connection is attempted.
 * <p>
 * Raised for violations including non-http(s) schemes, URLs that carry embedded credentials, missing or blank hosts,
 * hostnames that cannot be resolved (fail closed), and resolved addresses that fall inside a blocklisted range such as
 * loopback, RFC 1918 private, link-local, unique-local, or multicast space.
 * <p>
 * Extends {@link RuntimeException} so callers are not forced to declare handling at every level; the proxy controller
 * is the single component expected to catch this type and translate it into a client-facing 403 Forbidden response.
 * <p>
 * Messages may include the target host and the violated rule, but must never include URL userinfo (credentials) or
 * other secrets.
 */
public class SsrfViolationException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a violation with a descriptive reason.
	 *
	 * @param message human-readable description of the violation, safe for logs (host and violated rule, never
	 *                credentials)
	 */
	public SsrfViolationException(String message) {
		super(message);
	}

	/**
	 * Creates a violation wrapping an underlying failure, preserving its stack trace.
	 * <p>
	 * Typical use is the fail-closed path, wrapping {@link UnknownHostException} when DNS resolution fails and the
	 * request must be denied because the destination could not be verified.
	 *
	 * @param message human-readable description of the violation, safe for logs (host and violated rule, never
	 *                credentials)
	 * @param cause   underlying failure to preserve, e.g. DNS resolution failure
	 */
	public SsrfViolationException(String message, Throwable cause) {
		super(message, cause);
	}
}
