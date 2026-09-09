package io.github.kxng0109.aegisgate.mcp.config;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HitlSecretLengthValidator")
class HitlSecretLengthValidatorTest {

	private final HitlSecretLengthValidator validator = new HitlSecretLengthValidator();

	@Test
	@DisplayName("null holder passes (covered by @NotNull)")
	void nullHolderPasses() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

	@Test
	@DisplayName("null value passes (covered by cascaded @NotBlank)")
	void nullValuePasses() {
		assertThat(validator.isValid(new SensitiveString(null), null)).isTrue();
	}

	@Test
	@DisplayName("short secrets fail, 32+ byte secrets pass")
	void lengthBoundary() {
		assertThat(validator.isValid(new SensitiveString("short"), null)).isFalse();
		assertThat(validator.isValid(new SensitiveString("x".repeat(31)), null)).isFalse();
		assertThat(validator.isValid(new SensitiveString("x".repeat(32)), null)).isTrue();
		assertThat(validator.isValid(
				new SensitiveString("test-only-hitl-secret-32-bytes-minimum!!"), null)).isTrue();
	}
}
