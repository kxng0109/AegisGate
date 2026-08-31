package io.github.kxng0109.aegisgate.admin.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.util.Set;

/**
 * Optional fields for updating a virtual API key.
 *
 * @param name             new label (or null to preserve)
 * @param rpmLimit         new RPM limit (or null to preserve)
 * @param tpmLimit         new TPM limit (or null to preserve)
 * @param allowedModels    new allowed models (or null to preserve)
 * @param allowedProviders new allowed providers (or null to preserve)
 * @param enabled          new enabled state (or null to preserve)
 */
public record UpdateKeyRequest(
		String name,

		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		Set<String> allowedModels,
		Set<String> allowedProviders,
		Boolean enabled
) {
}
