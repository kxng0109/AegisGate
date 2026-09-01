package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(name = "UpdateKeyRequest", description = "Patch payload for modifying virtual key quotas, allowlists, or enabled status")
public record UpdateKeyRequest(
		@Schema(description = "New label for the key (optional)", example = "updated-production-key")
		String name,

		@Schema(description = "New RPM quota (optional, 0 = unlimited)", example = "240")
		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@Schema(description = "New TPM quota (optional, 0 = unlimited)", example = "1000000")
		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		@Schema(description = "New allowed model aliases set (optional)", example = "[\"gpt-56-luna\", \"claude-sonnet-4-5\"]")
		Set<String> allowedModels,

		@Schema(description = "New allowed providers set (optional)", example = "[\"openai\", \"anthropic\"]")
		Set<String> allowedProviders,

		@Schema(description = "Enable or disable key (optional)", example = "true")
		Boolean enabled
) {
}
