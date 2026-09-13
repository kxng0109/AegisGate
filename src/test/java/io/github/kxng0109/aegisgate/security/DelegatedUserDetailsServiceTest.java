package io.github.kxng0109.aegisgate.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DelegatedUserDetailsService}: with no local accounts configured, every lookup fails closed.
 *
 * <p>The bean's production purpose is twofold — it is the seam future human authentication attaches to, and its mere
 * presence switches off Boot's {@code UserDetailsServiceAutoConfiguration} so no generated development password is
 * ever minted or logged.</p>
 */
@DisplayName("DelegatedUserDetailsService")
class DelegatedUserDetailsServiceTest {

	private final DelegatedUserDetailsService service = new DelegatedUserDetailsService();

	@Test
	@DisplayName("unknown usernames are rejected")
	void unknownUserRejected() {
		assertThatThrownBy(() -> service.loadUserByUsername("admin"))
				.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	@DisplayName("empty usernames are rejected")
	void emptyUserRejected() {
		assertThatThrownBy(() -> service.loadUserByUsername(""))
				.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("null usernames are rejected without a NullPointerException")
	void nullUserRejected() {
		assertThatThrownBy(() -> service.loadUserByUsername(null))
				.isInstanceOf(UsernameNotFoundException.class);
	}
}
