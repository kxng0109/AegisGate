package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SsrfUpstreamUrlValidator}: it must delegate to the SSRF control and surface its violations
 * unchanged.
 */
@DisplayName("SsrfUpstreamUrlValidator")
class SsrfUpstreamUrlValidatorTest {

	@Test
	@DisplayName("a public IP passes without DNS")
	void publicUrlPasses() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of());
		// A literal public IP performs no DNS resolution and is deterministic:
		// the test must not depend on external DNS availability.
		assertDoesNotThrow(() -> validator.validate(URI.create("https://8.8.8.8/v1")));
	}

	@Test
	@DisplayName("a blocked URL surfaces the violation")
	void blockedUrlRejected() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of());
		assertThrows(
				SsrfViolationException.class,
				() -> validator.validate(URI.create("http://127.0.0.1:8080/v1"))
		);
	}

	@Test
	@DisplayName("default allowlist is empty: private hosts still fail closed")
	void defaultDenyPrivateHosts() {
		SsrfUpstreamUrlValidator validator = new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of());
		assertFalse(validator.isPrivateHostAllowed("localhost"));
		assertFalse(validator.isPrivateHostAllowed("host.docker.internal"));
		assertThrows(
				SsrfViolationException.class,
				() -> validator.validate(URI.create("http://localhost:1234/v1"))
		);
	}

	@Test
	@DisplayName("explicit allowlist waives only the private-range verdict")
	void allowlistedPrivateHostPasses() {
		// localhost always resolves privately, so passing proves the waiver.
		SsrfUpstreamUrlValidator validator =
				new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of("localhost"));
		assertTrue(validator.isPrivateHostAllowed("LOCALHOST"));
		assertDoesNotThrow(() -> validator.validate(URI.create("http://localhost:1234/v1")));
	}

	@Test
	@DisplayName("allowlisting still enforces scheme, userinfo, and resolvability")
	void allowlistedHostKeepsOtherControls() {
		SsrfUpstreamUrlValidator validator =
				new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of("localhost"));
		assertThrows(
				SsrfViolationException.class,
				() -> validator.validate(URI.create("ftp://localhost:1234/v1"))
		);
		assertThrows(
				SsrfViolationException.class,
				() -> validator.validate(URI.create("http://u:p@localhost:1234/v1"))
		);
		SsrfUpstreamUrlValidator other =
				new SsrfUpstreamUrlValidator(new SsrfValidator(), Set.of("other.invalid"));
		assertThrows(
				SsrfViolationException.class,
				() -> other.validate(URI.create("http://other.invalid/v1"))
		);
		assertFalse(other.isPrivateHostAllowed(null));
	}
}