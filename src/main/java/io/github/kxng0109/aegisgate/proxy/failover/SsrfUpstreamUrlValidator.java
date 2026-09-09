package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link UpstreamUrlValidator} backed by the gateway's SSRF control.
 *
 * <p>Provider URLs originate from trusted configuration rather than client
 * input, so the SSRF check is a defense in depth layer that guards against misconfiguration and future dynamic targets.
 * The check runs once per provider, lazily, before the first attempt.</p>
 *
 * <p>Local development backends (LM Studio, Ollama, vLLM) resolve to private
 * addresses by design. They are allowlisted explicitly via
 * {@code gateway.dev.allow-private-hosts} (comma-separated, empty by default):
 * allowlisting is exact-host, never a subnet, and every non-allowlisted private
 * target still fails closed.</p>
 */
@Component
public class SsrfUpstreamUrlValidator implements UpstreamUrlValidator {

	private final SsrfValidator delegate;
	private final Set<String> allowPrivateHosts;

	/**
	 * @param delegate          the SSRF validator to delegate to
	 * @param allowPrivateHosts exact hostnames permitted to resolve privately
	 */
	public SsrfUpstreamUrlValidator(
			SsrfValidator delegate,
			@Value("${gateway.dev.allow-private-hosts:}") Set<String> allowPrivateHosts
	) {
		this.delegate = delegate;
		this.allowPrivateHosts = allowPrivateHosts.stream()
		                                          .map(h -> h.toLowerCase(Locale.ROOT))
		                                          .collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * @param targetUrl the URL about to be contacted
	 * @throws SsrfViolationException when the target is unsafe or unresolvable
	 */
	@Override
	public void validate(URI targetUrl) {
		String host = targetUrl != null ? targetUrl.getHost() : null;
		if (host != null && allowPrivateHosts.contains(host.toLowerCase(Locale.ROOT))) {
			validateAllowlisted(targetUrl, host);
			return;
		}
		delegate.validate(targetUrl);
	}

	/**
	 * Validates an allowlisted host with the full control minus the private-range rule: scheme, userinfo, and
	 * resolvability are still enforced, so only the private-address verdict is waived for an explicitly trusted name.
	 */
	private void validateAllowlisted(URI targetUrl, String host) {
		String scheme = targetUrl.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new SsrfViolationException(
					"unsupported scheme '" + scheme + "': only http and https are permitted");
		}
		if (targetUrl.getUserInfo() != null) {
			throw new SsrfViolationException(
					"URL must not embed credentials in userinfo for host '" + host + "'");
		}
		try {
			// getAllByName returns at least one address or throws UnknownHostException;
			// either way an unresolvable host fails closed below.
			InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new SsrfViolationException(
					"host '" + host + "' could not be resolved; failing closed", e);
		}
	}

	/**
	 * @param host the target host
	 * @return true when the host is explicitly allowlisted for private resolution
	 */
	@Override
	public boolean isPrivateHostAllowed(String host) {
		return host != null && allowPrivateHosts.contains(host.toLowerCase(Locale.ROOT));
	}
}