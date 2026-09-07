package io.github.kxng0109.aegisgate.mcp.config;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link HitlSecretLength} against a {@link SensitiveString} holder. Null holders pass (covered by
 * {@code @NotNull}); blank values fail the length check and surface the length message.
 */
public final class HitlSecretLengthValidator implements ConstraintValidator<HitlSecretLength, SensitiveString> {

	@Override
	public boolean isValid(SensitiveString secret, ConstraintValidatorContext context) {
		if (secret == null || secret.value() == null) {
			return true;
		}
		return secret.value().length() >= 32;
	}
}
