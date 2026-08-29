package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SsrfUpstreamUrlValidator}: it must delegate to the SSRF control and surface its violations
 * unchanged.
 */
@DisplayName("SsrfUpstreamUrlValidator")
class SsrfUpstreamUrlValidatorTest {

	@Test
	@DisplayName("a public IP passes without DNS")
	void publicUrlPasses() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator());
		// A literal public IP performs no DNS resolution and is deterministic:
		// the test must not depend on external DNS availability.
		assertDoesNotThrow(() -> validator.validate(URI.create("https://8.8.8.8/v1")));
	}

	@Test
	@DisplayName("a blocked URL surfaces the violation")
	void blockedUrlRejected() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator());
		assertThrows(
				SsrfViolationException.class,
				() -> validator.validate(URI.create("http://127.0.0.1:8080/v1"))
		);
	}
}