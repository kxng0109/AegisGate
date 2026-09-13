package io.github.kxng0109.aegisgate.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Local-accounts authority seam for human authentication.
 *
 * <p>Today the gateway has no human login: machine routes authenticate via bearer-key filters and the only
 * {@code SecurityFilterChain} is fail-closed. This bean exists for two reasons. First, declaring any
 * {@code UserDetailsService} switches off Boot's {@code UserDetailsServiceAutoConfiguration}, which otherwise mints
 * an in-memory {@code user} with a random password and prints it to the logs — a dormant credential with no
 * legitimate consumer. Second, it is the seam the future login UI attaches to: local username+password arrives as a
 * new implementation of this interface (backed by the admin store), and external SSO arrives as additional
 * {@code AuthenticationProvider}s / {@code oauth2Login} / {@code saml2Login} chains. The filter chain, the delegated
 * machine filters, and the deny-all posture do not move.</p>
 *
 * <p>The v1 implementation rejects every lookup: there are no local accounts yet, so any authentication attempt
 * against this service must fail closed.</p>
 *
 * @since 1.8.0
 */
@Service
public class DelegatedUserDetailsService implements UserDetailsService {

	/**
	 * Always fails: no local accounts exist.
	 *
	 * @param username the presented username; never {@code null} in practice
	 * @return never returns normally
	 * @throws UsernameNotFoundException always, to fail closed
	 */
	@Override
	public UserDetails loadUserByUsername(String username) {
		throw new UsernameNotFoundException("no local accounts are configured");
	}
}
