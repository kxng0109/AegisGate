package io.github.kxng0109.aegisgate.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial SSRF bypass coverage: IPv4-mapped / translation-prefix encoded internals, transition-technology
 * prefixes, host-normalization quirks. All cases are literal IPs (no DNS), so they are deterministic in any
 * environment including CI.
 */
class SsrfValidatorBypassTest {

	private final SsrfValidator validator = new SsrfValidator();

	private static InetAddress ip(String address) throws UnknownHostException {
		return InetAddress.getByName(address);
	}

	@Test
	@DisplayName("rejects IPv4-mapped IPv6 loopback")
	void rejectsIpv4MappedLoopback() {
		assertThatThrownBy(() -> validator.validate(URI.create("http://[::ffff:127.0.0.1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
	}

	@Test
	@DisplayName("rejects IPv4-mapped IPv6 private addresses")
	void rejectsIpv4MappedPrivate() {
		assertThatThrownBy(() -> validator.validate(URI.create("http://[::ffff:10.0.0.1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
		assertThatThrownBy(() -> validator.validate(URI.create("http://[::ffff:192.168.0.1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
	}

	@Test
	@DisplayName("still accepts IPv4-mapped form of a public address")
	void acceptsIpv4MappedPublic() {
		validator.validate(URI.create("http://[::ffff:93.184.216.34]/v1"));
	}

	@Test
	@DisplayName("rejects NAT64-encoded private addresses")
	void rejectsNat64EncodedPrivate() {
		assertThatThrownBy(() -> validator.validate(URI.create("http://[64:ff9b::a00:1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
	}

	@Test
	@DisplayName("rejects Teredo and 6to4 transition prefixes")
	void rejectsTransitionPrefixes() {
		assertThatThrownBy(() -> validator.validate(URI.create("http://[2001::1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
		assertThatThrownBy(() -> validator.validate(URI.create("http://[2002:5db8:d822::1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
	}

	@Test
	@DisplayName("rejects IPv6 link-local, discard and unspecified")
	void rejectsIpv6SpecialRanges() {
		assertThatThrownBy(() -> validator.validate(URI.create("http://[fe80::1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
		assertThatThrownBy(() -> validator.validate(URI.create("http://[100::1]/v1")))
				.isInstanceOf(SsrfViolationException.class);
		assertThatThrownBy(() -> validator.validate(URI.create("http://[::]/v1")))
				.isInstanceOf(SsrfViolationException.class);
	}

	@Test
	@DisplayName("normalizes hosts deterministically without network I/O")
	void normalizesHostsDeterministically() {
		assertThat(SsrfValidator.normalizeHost("LOCALHOST.")).isEqualTo("localhost");
		assertThat(SsrfValidator.normalizeHost("93.184.216.34.")).isEqualTo("93.184.216.34");
		assertThat(SsrfValidator.normalizeHost("93.184.216.34")).isEqualTo("93.184.216.34");
		assertThat(SsrfValidator.normalizeHost(".")).isEqualTo(".");
		assertThat(SsrfValidator.normalizeHost("a".repeat(64))).isEqualTo("a".repeat(64));
	}

	@Test
	@DisplayName("JDK collapses mapped literals to IPv4 (contract CidrRange relies on)")
	void jdkCollapsesMappedLiteralsToIpv4() throws Exception {
		assertThat(ip("::ffff:127.0.0.1").getAddress()).hasSize(4);
		assertThat(ip("::ffff:93.184.216.34").getAddress()).hasSize(4);
	}

	@Test
	@DisplayName("CidrRange unwraps mapped candidates before matching IPv4 ranges")
	void cidrRangeUnwrapsMappedCandidates() throws Exception {
		CidrRange loopback = new CidrRange(ip("127.0.0.0"), 8);
		assertThat(loopback.contains(ip("::ffff:127.0.0.1"))).isTrue();
		assertThat(loopback.contains(ip("::ffff:93.184.216.34"))).isFalse();		CidrRange privateTen = new CidrRange(ip("10.0.0.0"), 8);
		assertThat(privateTen.contains(ip("::ffff:10.0.0.1"))).isTrue();

		CidrRange nat64 = new CidrRange(ip("10.0.0.0"), 8);
		assertThat(nat64.contains(ip("64:ff9b::a00:1"))).isTrue();
		assertThat(nat64.contains(ip("64:ff9b::5db8:d822"))).isFalse();
		assertThat(nat64.contains(ip("64:ff9b:0:1::1"))).isFalse();
	}

	@Test
	@DisplayName("CidrRange rejects cross-family candidates without embedded IPv4")
	void cidrRangeRejectsCrossFamilyWithoutEmbeddedIpv4() throws Exception {
		CidrRange privateTen = new CidrRange(ip("10.0.0.0"), 8);
		assertThat(privateTen.contains(ip("::1"))).isFalse();
		assertThat(privateTen.contains(ip("fe80::1"))).isFalse();

		CidrRange loopback6 = new CidrRange(ip("::1"), 128);
		assertThat(loopback6.contains(ip("127.0.0.1"))).isFalse();
	}
}
