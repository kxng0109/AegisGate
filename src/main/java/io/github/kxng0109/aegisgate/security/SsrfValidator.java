package io.github.kxng0109.aegisgate.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Validates target URLs against SSRF attack vectors before any outbound connection is made.
 * <p>
 * Implements the deny-list strategy of the OWASP SSRF Prevention Cheat Sheet as a defense-in-depth layer. The primary
 * SSRF control for this gateway is architectural: upstream targets originate exclusively from trusted gateway
 * configuration, never from client input. This validator hardens the path for the phases where targets may become
 * dynamic.
 * <p>
 * Every address the host resolves to (A and AAAA records) is checked against the blocklist, so an attacker cannot slip
 * through by pointing one record of a multi-record host inward. Validation fails closed: a URL whose host cannot be
 * resolved is rejected, because an unverifiable destination must not be contacted. Resolution of literal IP addresses
 * performs no network I/O, keeping validation of literal targets fast and deterministic.
 */
@Component
public class SsrfValidator {

	/**
	 * Ranges no legitimate public LLM provider can live in, per the OWASP minimum deny-list: cloud metadata lives
	 * inside link-local space; loopback, RFC 1918 private, this-network, and multicast cover the remaining internal
	 * attack surface for both address families.
	 */
	private static final List<CidrRange> BLOCKED_RANGES = List.of(
			new CidrRange(InetAddress.ofLiteral("0.0.0.0"), 8),
			new CidrRange(InetAddress.ofLiteral("10.0.0.0"), 8),
			new CidrRange(InetAddress.ofLiteral("172.16.0.0"), 12),
			new CidrRange(InetAddress.ofLiteral("192.168.0.0"), 16),
			new CidrRange(InetAddress.ofLiteral("169.254.0.0"), 16),
			new CidrRange(InetAddress.ofLiteral("127.0.0.0"), 8),
			new CidrRange(InetAddress.ofLiteral("224.0.0.0"), 4),
			new CidrRange(InetAddress.ofLiteral("::1"), 128),
			new CidrRange(InetAddress.ofLiteral("fc00::"), 7),
			new CidrRange(InetAddress.ofLiteral("ff00::"), 8)
	);

	/**
	 * Rejects URLs that are unsafe to connect to; returns normally when the URL passes.
	 * <p>
	 * The checks run in escalation order so that cheap syntactic rejections fire before any DNS work: null target,
	 * non-http(s) scheme, embedded credentials, missing host, unresolvable host (fail closed), then the blocklist over
	 * all resolved addresses.
	 *
	 * @param targetUrl the URL about to be contacted by the gateway
	 * @throws SsrfViolationException if any check fails; the message names the host and the violated rule and never
	 *                                contains URL credentials
	 */
	public void validate(URI targetUrl) {
		if (targetUrl == null) {
			throw new SsrfViolationException("target URL must not be null");
		}

		String scheme = targetUrl.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new SsrfViolationException(
					"unsupported scheme '" + scheme + "': only http and https are permitted");
		}

		if (targetUrl.getUserInfo() != null) {
			throw new SsrfViolationException(
					"URL must not embed credentials in userinfo for host '" + targetUrl.getHost() + "'");
		}

		String host = targetUrl.getHost();
		if (host == null) {
			throw new SsrfViolationException("URL has no resolvable host");
		}

		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new SsrfViolationException(
					"host '" + host + "' could not be resolved; failing closed", e);
		}

		for (InetAddress address : addresses) {
			for (CidrRange range : BLOCKED_RANGES) {
				if (range.contains(address)) {
					throw new SsrfViolationException("host '" + host
							                                 + "' resolves to blocked address "
							                                 + address.getHostAddress()
							                                 + " within range " + range.networkAddress()
							                                                           .getHostAddress()
							                                 + "/" + range.prefixLength());
				}
			}
		}
	}
}
