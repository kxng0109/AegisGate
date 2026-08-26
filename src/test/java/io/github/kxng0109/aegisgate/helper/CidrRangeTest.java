package io.github.kxng0109.aegisgate.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class CidrRangeTest {

	private static InetAddress ip(String address) throws UnknownHostException {
		return InetAddress.getByName(address);
	}

	@Test
	@DisplayName("contains accepts addresses inside an IPv4 /16 range")
	void containsAcceptsAddressesInsideSlash16() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.0.0"), 16);

		assertTrue(range.contains(ip("192.168.5.10")));
		assertTrue(range.contains(ip("192.168.255.255")));
	}

	@Test
	@DisplayName("contains rejects addresses outside an IPv4 /16 range")
	void containsRejectsAddressesOutsideSlash16() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.0.0"), 16);

		assertFalse(range.contains(ip("192.169.0.0")));
		assertFalse(range.contains(ip("172.16.3.9")));
	}

	@Test
	@DisplayName("contains handles a partial-byte prefix (/12)")
	void containsHandlesPartialBytePrefix() throws Exception {
		CidrRange range = new CidrRange(ip("172.16.0.0"), 12);

		assertTrue(range.contains(ip("172.20.3.5")));
		assertTrue(range.contains(ip("172.31.255.255")));
		assertFalse(range.contains(ip("172.32.0.1")));
		assertFalse(range.contains(ip("172.15.255.255")));
	}

	@Test
	@DisplayName("contains accepts only the exact host for a /32 range")
	void containsMatchesExactHostOnlyForSlash32() throws Exception {
		CidrRange range = new CidrRange(ip("127.0.0.1"), 32);

		assertTrue(range.contains(ip("127.0.0.1")));
		assertFalse(range.contains(ip("127.0.0.2")));
	}

	@Test
	@DisplayName("contains works for IPv6 ranges")
	void containsWorksForIpv6Ranges() throws Exception {
		CidrRange loopback = new CidrRange(ip("::1"), 128);
		assertTrue(loopback.contains(ip("::1")));
		assertFalse(loopback.contains(ip("fe80::1")));

		CidrRange uniqueLocal = new CidrRange(ip("fc00::"), 7);
		assertTrue(uniqueLocal.contains(ip("fc00::1")));
		assertTrue(uniqueLocal.contains(ip("fd12::1")));
		assertFalse(uniqueLocal.contains(ip("fe80::1")));
	}

	@Test
	@DisplayName("contains returns false when candidate family differs from range family")
	void containsReturnsFalseOnFamilyMismatch() throws Exception {
		CidrRange ipv4Range = new CidrRange(ip("192.168.0.0"), 16);

		assertFalse(ipv4Range.contains(ip("::1")));
	}

	@Test
	@DisplayName("constructor rejects a null network address")
	void constructorRejectsNullAddress() {
		assertThrows(NullPointerException.class,
				() -> new CidrRange((InetAddress) null, 8));
	}

	@Test
	@DisplayName("constructor rejects negative prefix length")
	void constructorRejectsNegativePrefix() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> new CidrRange(ip("192.168.0.0"), -1));
	}

	@Test
	@DisplayName("constructor enforces the inclusive maximum prefix per address family")
	void constructorEnforcesFamilyMaximumPrefix() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> new CidrRange(ip("192.168.0.0"), 33));
		assertThrows(IllegalArgumentException.class,
				() -> new CidrRange(ip("fc00::"), 129));

		assertDoesNotThrow(() -> new CidrRange(ip("192.168.0.0"), 32));
		assertDoesNotThrow(() -> new CidrRange(ip("fc00::"), 128));
	}

	@Test
	@DisplayName("contains accepts addresses across an IPv4 /8 range")
	void containsAcceptsAddressesInsideSlash8() throws Exception {
		CidrRange range = new CidrRange(ip("10.0.0.0"), 8);

		assertTrue(range.contains(ip("10.4.9.12")));
		assertTrue(range.contains(ip("10.255.255.255")));
		assertFalse(range.contains(ip("11.0.0.1")));
	}

	@Test
	@DisplayName("contains handles an IPv6 /64 subnet")
	void containsHandlesIpv6Slash64Subnet() throws Exception {
		CidrRange range = new CidrRange(ip("2001:db8:1234:5678::"), 64);

		assertTrue(range.contains(ip("2001:db8:1234:5678::1")));
		assertTrue(range.contains(ip("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd")));
		assertFalse(range.contains(ip("2001:db8:1234:5679::")));
	}

	@Test
	@DisplayName("contains handles a partial-byte IPv6 prefix (/33)")
	void containsHandlesPartialByteIpv6Prefix() throws Exception {
		CidrRange range = new CidrRange(ip("2001:db8::"), 33);

		assertTrue(range.contains(ip("2001:db8::1")));
		assertFalse(range.contains(ip("2001:db8:8000::1")));
	}

	@Test
	@DisplayName("contains rejects neighbors of an IPv6 /128 loopback range")
	void containsRejectsNeighborsOfIpv6Loopback() throws Exception {
		CidrRange loopback = new CidrRange(ip("::1"), 128);

		assertTrue(loopback.contains(ip("::1")));
		assertTrue(loopback.contains(ip("0:0:0:0:0:0:0:1")));
		assertFalse(loopback.contains(ip("::2")));
	}

	@Test
	@DisplayName("contains enforces the exact boundaries of fc00::/7")
	void containsEnforcesBoundariesOfUniqueLocalRange() throws Exception {
		CidrRange uniqueLocal = new CidrRange(ip("fc00::"), 7);

		assertTrue(uniqueLocal.contains(ip("fc00::")));
		assertTrue(uniqueLocal.contains(ip("fdff::1")));
		assertFalse(uniqueLocal.contains(ip("fe00::1")));
	}

	@Test
	@DisplayName("contains treats IPv4-mapped IPv6 candidates as IPv4")
	void containsTreatsIpv4MappedCandidatesAsIpv4() throws Exception {
		CidrRange privateRange = new CidrRange(ip("192.168.0.0"), 16);
		CidrRange linkLocalRange = new CidrRange(ip("169.254.0.0"), 16);

		assertTrue(privateRange.contains(ip("::ffff:192.168.5.10")));
		assertTrue(linkLocalRange.contains(ip("::ffff:169.254.169.254")));
	}

	@Test
	@DisplayName("contains throws when the candidate is null")
	void containsThrowsOnNullCandidate() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.0.0"), 16);

		assertThrows(NullPointerException.class, () -> range.contains(null));
	}

	@Test
	@DisplayName("a zero prefix matches every address of the same family")
	void zeroPrefixMatchesEveryAddressOfSameFamily() throws Exception {
		CidrRange ipv4Everything = new CidrRange(ip("0.0.0.0"), 0);
		assertTrue(ipv4Everything.contains(ip("8.8.8.8")));
		assertTrue(ipv4Everything.contains(ip("203.0.113.99")));

		CidrRange ipv6Everything = new CidrRange(ip("::"), 0);
		assertTrue(ipv6Everything.contains(ip("2001:db8::1")));
	}

	@Test
	@DisplayName("contains handles a byte-aligned IPv4 /24 range")
	void containsHandlesByteAlignedSlash24() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.5.0"), 24);

		assertTrue(range.contains(ip("192.168.5.1")));
		assertTrue(range.contains(ip("192.168.5.254")));
		assertFalse(range.contains(ip("192.168.6.0")));
		assertFalse(range.contains(ip("192.168.4.255")));
	}

	@Test
	@DisplayName("contains handles another partial-byte prefix (/22)")
	void containsHandlesPartialByteSlash22() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.4.0"), 22);

		assertTrue(range.contains(ip("192.168.4.1")));
		assertTrue(range.contains(ip("192.168.7.254")));
		assertFalse(range.contains(ip("192.168.3.255")));
		assertFalse(range.contains(ip("192.168.8.0")));
	}

	@Test
	@DisplayName("contains ignores host bits set on the network address")
	void containsIgnoresHostBitsInNetworkAddress() throws Exception {
		CidrRange range = new CidrRange(ip("192.168.5.10"), 16);

		assertTrue(range.contains(ip("192.168.200.7")));
		assertTrue(range.contains(ip("192.168.5.10")));
	}

	@Test
	@DisplayName("contains covers the loopback /8 used by gateway blocklists")
	void containsCoversLoopbackSlash8() throws Exception {
		CidrRange range = new CidrRange(ip("127.0.0.0"), 8);

		assertTrue(range.contains(ip("127.0.0.1")));
		assertTrue(range.contains(ip("127.250.1.2")));
		assertFalse(range.contains(ip("126.255.255.255")));
		assertFalse(range.contains(ip("128.0.0.0")));
	}

	@Test
	@DisplayName("contains covers the cloud metadata address inside link-local")
	void containsCoversCloudMetadataWithinLinkLocal() throws Exception {
		CidrRange range = new CidrRange(ip("169.254.0.0"), 16);

		assertTrue(range.contains(ip("169.254.169.254")));
		assertTrue(range.contains(ip("169.254.0.1")));
		assertFalse(range.contains(ip("169.253.255.255")));
	}

	@Test
	@DisplayName("contains covers the IPv6 multicast ff00::/8 range")
	void containsHandlesIpv6MulticastSlash8() throws Exception {
		CidrRange multicast = new CidrRange(ip("ff00::"), 8);

		assertTrue(multicast.contains(ip("ff02::1")));
		assertTrue(multicast.contains(ip("ff05::1")));
		assertFalse(multicast.contains(ip("fe00::1")));
		assertFalse(multicast.contains(ip("2001:db8::1")));
	}

	@Test
	@DisplayName("record contract: equality, hash code, accessors, and toString behave as expected")
	void recordContractHoldsForEqualityHashAccessorsAndToString() throws Exception {
		InetAddress network = ip("10.0.0.0");
		CidrRange range = new CidrRange(network, 8);

		assertEquals(range, new CidrRange(ip("10.0.0.0"), 8));
		assertEquals(range.hashCode(), new CidrRange(ip("10.0.0.0"), 8).hashCode());
		assertNotEquals(range, new CidrRange(ip("10.0.0.0"), 16));
		assertNotEquals(range, new CidrRange(ip("172.16.0.0"), 8));

		assertEquals(network, range.networkAddress());
		assertEquals(8, range.prefixLength());
		assertTrue(range.toString().contains("8"));
	}
}
