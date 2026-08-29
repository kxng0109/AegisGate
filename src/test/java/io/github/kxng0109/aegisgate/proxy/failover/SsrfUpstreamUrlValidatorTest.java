package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SsrfUpstreamUrlValidator}: it must delegate to the
 * SSRF control and surface its violations unchanged.
 */
@DisplayName("SsrfUpstreamUrlValidator")
class SsrfUpstreamUrlValidatorTest {

	@Test
	@DisplayName("a public URL passes through")
	void publicUrlPasses() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator());
		assertDoesNotThrow(() -> validator.validate(URI.create("https://api.openai.com/v1")));
	}

	@Test
	@DisplayName("a blocked URL surfaces the violation")
	void blockedUrlRejected() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator());
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://127.0.0.1:8080/v1"))
		);
	}
}