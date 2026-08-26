package io.github.kxng0109.aegisgate.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SsrfValidatorTest {

	private final SsrfValidator validator = new SsrfValidator();

	@Test
	@DisplayName("rejects a null target URL")
	void rejectsNullTargetUrl() {
		SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
		                                             () -> validator.validate(null)
		);

		assertTrue(thrown.getMessage().contains("must not be null"));
	}

	@Test
	@DisplayName("rejects non-http(s) schemes")
	void rejectsNonHttpSchemes() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("ftp://93.184.216.34/resources"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("file:///etc/passwd"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("gopher://93.184.216.34:70/"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("//93.184.216.34/path"))
		);
	}

	@Test
	@DisplayName("accepts schemes case-insensitively")
	void acceptsSchemesCaseInsensitively() {
		assertDoesNotThrow(() -> validator.validate(URI.create("HTTP://93.184.216.34/v1")));
		assertDoesNotThrow(() -> validator.validate(URI.create("HTTPS://93.184.216.34/v1")));
	}

	@Test
	@DisplayName("rejects URLs that embed credentials in userinfo")
	void rejectsUrlsThatEmbedCredentials() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://user:pass@93.184.216.34/v1"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("https://token@93.184.216.34/"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://@169.254.169.254/latest/meta-data/"))
		);
	}

	@Test
	@DisplayName("violation messages never leak embedded credentials")
	void violationMessagesNeverLeakEmbeddedCredentials() {
		SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
		                                             () -> validator.validate(
				                                             URI.create("http://super-secret-token@93.184.216.34/"))
		);

		assertFalse(thrown.getMessage().contains("super-secret-token"));
	}

	@Test
	@DisplayName("rejects URLs with no resolvable host")
	void rejectsWithNoResolvableHost() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http:///path/without/host"))
		);
	}

	@Test
	@DisplayName("rejects IPv4 loopback literals across the /8 range")
	void rejectsIpv4LoopbackLiterals() {
		for (String ip : new String[]{"127.0.0.1", "127.250.1.2", "127.255.255.254"}) {
			SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
			                                             () -> validator.validate(URI.create("http://" + ip + "/v1"))
			);

			assertEquals(ip, hostOf(thrown));
		}
	}

	@Test
	@DisplayName("rejects localhost resolved via the local resolver")
	void rejectsLocalHostname() {
		SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
		                                             () -> validator.validate(URI.create("http://localhost:8080/v1"))
		);

		assertTrue(thrown.getMessage().contains("localhost"));
	}

	@Test
	@DisplayName("rejects every RFC 1918 private range")
	void rejectsEveryRfc1918Range() {
		String[][] cases = {
				{"10.4.9.12", "10.0.0.0"},
				{"172.20.3.5", "172.16.0.0"},
				{"192.168.5.10", "192.168.0.0"}
		};
		for (String[] c : cases) {
			SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
			                                             () -> validator.validate(URI.create("http://" + c[0] + "/v1"))
			);

			assertTrue(thrown.getMessage().contains(c[0]),
			           "message should name the offending address"
			);
			assertTrue(thrown.getMessage().contains(c[1] + "/"),
			           "message should name the matched range"
			);
		}
	}

	@Test
	@DisplayName("rejects link-local addresses including the cloud metadata service")
	void rejectsLinkLocalAndCloudMetadata() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://169.254.169.254/latest/meta-data/"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://169.254.1.1/"))
		);
	}

	@Test
	@DisplayName("rejects this-network and multicast addresses")
	void rejectsThisNetworkAndMulticast() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://0.0.0.0/"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://224.0.0.1/"))
		);
	}

	@Test
	@DisplayName("rejects bracketed IPv6 loopback and unique-local targets")
	void rejectsBracketedIpv6InternalTargets() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://[::1]/v1"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://[fc00::1]/v1"))
		);
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://[fd12::1]/v1"))
		);
	}

	@Test
	@DisplayName("rejects IPv6 multicast targets")
	void rejectsIpv6MulticastTargets() {
		assertThrows(SsrfViolationException.class,
		             () -> validator.validate(URI.create("http://[ff02::1]/v1"))
		);
	}

	@Test
	@DisplayName("accepts a public IPv4 literal URL")
	void acceptsPublicIpv4Literal() {
		assertDoesNotThrow(() ->
				                   validator.validate(URI.create("http://93.184.216.34/v1/chat/completions")));
	}

	@Test
	@DisplayName("accepts a public https literal with port and path")
	void acceptsPublicHttpsLiteralWithPortAndPath() {
		assertDoesNotThrow(() ->
				                   validator.validate(
						                   URI.create("https://93.184.216.34:8443/openai/v1/chat/completions")));
	}

	@Test
	@DisplayName("accepts an IPv4-mapped IPv6 form of a public address")
	void acceptsIpv4MappedFormOfPublicAddress() {
		assertDoesNotThrow(() ->
				                   validator.validate(URI.create("http://[::ffff:93.184.216.34]/v1")));
	}

	@Test
	@DisplayName("fails closed when the host cannot be resolved")
	void failsClosedOnUnresolvableHostname() {
		SsrfViolationException thrown = assertThrows(SsrfViolationException.class,
		                                             () -> validator.validate(
				                                             URI.create("http://aegisgate-nonexistent-host.invalid/v1"))
		);

		assertTrue(thrown.getMessage().contains("could not be resolved"));
		assertInstanceOf(UnknownHostException.class, thrown.getCause());
	}

	private String hostOf(SsrfViolationException thrown) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("host '([^']+)'")
				.matcher(thrown.getMessage());
		return matcher.find() ? matcher.group(1) : "";
	}
}
