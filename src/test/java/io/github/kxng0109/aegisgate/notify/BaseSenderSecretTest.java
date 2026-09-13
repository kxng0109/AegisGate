package io.github.kxng0109.aegisgate.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for secret-reference resolution: null, blank, malformed, absent-environment, and present-value
 * branches. Validates the allowlist shape and the never-leak-null contract the senders rely on.
 */
@DisplayName("BaseSender secret resolution")
class BaseSenderSecretTest {

	@Test
	@DisplayName("null references resolve empty")
	void nullRefResolvesEmpty() {
		assertThat(BaseSender.resolveSecret(null)).isNull();
	}

	@Test
	@DisplayName("blank references resolve empty")
	void blankRefResolvesEmpty() {
		assertThat(BaseSender.resolveSecret("")).isNull();
		assertThat(BaseSender.resolveSecret("  ")).isNull();
	}

	@Test
	@DisplayName("malformed references resolve empty")
	void malformedRefResolvesEmpty() {
		assertThat(BaseSender.resolveSecret("bad ref!")).isNull();
		assertThat(BaseSender.resolveSecret("lower")).isNull();
	}

	@Test
	@DisplayName("a present environment variable resolves to its value")
	void presentEnvResolves() {
		assertThat(BaseSender.resolveSecret("PATH")).isEqualTo(System.getenv("PATH"));
	}

	@Test
	@DisplayName("an absent environment variable resolves empty")
	void absentEnvResolvesEmpty() {
		assertThat(BaseSender.resolveSecret("AEGIS_TEST_ABSENT_XYZ")).isNull();
	}
}