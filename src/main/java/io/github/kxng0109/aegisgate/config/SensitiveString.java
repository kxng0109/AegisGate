package io.github.kxng0109.aegisgate.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Wraps a sensitive string value, masking its contents in {@code toString()} output to prevent accidental exposure in
 * logs or diagnostic output.
 *
 * <p>Designed for use in configuration objects (e.g. API keys, credentials) where the value must be validated and bound
 * at startup,
 * with validation enforced via {@link org.springframework.validation.annotation.Validated Validated } to ensure empty
 * or blank values fail fast during configuration binding rather than causing runtime issues later.</p>
 *
 * @param value sensitive value to wrap; must not be blank
 */
public record SensitiveString(
		@NotBlank(message = "Value required!")
		String value
) {
	public String toString() {
		return "****";
	}
}
