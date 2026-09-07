package io.github.kxng0109.aegisgate.mcp.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that a HITL AEAD secret carries at least 32 bytes of key material.
 *
 * <p>Scoped to the HITL resumption-token secret only. Must NOT be placed on the shared
 * {@code SensitiveString} record component — provider keys (e.g. {@code sk-test}) are legitimately shorter and share
 * that type.</p>
 */
@Documented
@Constraint(validatedBy = HitlSecretLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface HitlSecretLength {

	String message() default "GATEWAY_MCP_HITL_SECRET must be at least 32 bytes";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
