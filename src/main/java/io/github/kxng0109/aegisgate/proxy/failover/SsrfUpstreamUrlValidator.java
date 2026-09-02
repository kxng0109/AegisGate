package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * {@link UpstreamUrlValidator} backed by the gateway's SSRF control.
 *
 * <p>Provider URLs originate from trusted configuration rather than client
 * input, so the SSRF check is a defense in depth layer that guards against misconfiguration and future dynamic targets.
 * The check runs once per provider, lazily, before the first attempt.</p>
 */
@Component
public class SsrfUpstreamUrlValidator implements UpstreamUrlValidator {

	private final SsrfValidator delegate;

	/**
	 * @param delegate the SSRF validator to delegate to
	 */
	public SsrfUpstreamUrlValidator(SsrfValidator delegate) {
		this.delegate = delegate;
	}

	/**
	 * @param targetUrl the URL about to be contacted
	 * @throws SsrfViolationException when the target is unsafe or unresolvable
	 */
	@Override
	public void validate(URI targetUrl) {
		delegate.validate(targetUrl);
	}
}