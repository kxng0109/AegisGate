package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.security.SsrfValidator;

import java.net.URI;

/**
 * Test-only SSRF validator that permits every target. Isolates proxy
 * streaming/forwarding logic from the production SSRF control, which is
 * validated independently in {@code SsrfValidatorTest}.
 */
final class AllowAllSsrfValidator extends SsrfValidator {
	@Override
	public void validate(URI targetUrl) {
		// intentionally permissive for in-process mock server testing
	}
}
