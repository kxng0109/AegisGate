package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Set;

/**
 * Request payload for creating a virtual API key.
 *
 * @param ownerId          owner or tenant identifier (required)
 * @param name             label for the key (required)
 * @param rpmLimit         requests per minute limit (0 = unlimited)
 * @param tpmLimit         tokens per minute limit (0 = unlimited)
 * @param allowedModels    allowed model names (null or empty = all allowed)
 * @param allowedProviders allowed provider names (null or empty = all allowed)
 */
@Schema(name = "CreateKeyRequest", description = "Payload for provisioning a new virtual API key with quotas and model access controls")
public record CreateKeyRequest(
		@Schema(description = "Owner or tenant identifier for billing attribution", example = "tenant-corp", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "ownerId must not be blank")
		String ownerId,

		@Schema(description = "Human-readable label for key identification", example = "production-engine-key", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "name must not be blank")
		String name,

		@Schema(description = "Requests per minute limit (0 = unlimited)", example = "120", minimum = "0")
		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@Schema(description = "Tokens per minute limit (0 = unlimited)", example = "500000", minimum = "0")
		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		@Schema(description = "Set of permitted model alias identifiers (empty = all allowed)", example = "[\"gpt-56-luna\", \"claude-sonnet-4-5\"]")
		Set<String> allowedModels,

		@Schema(description = "Set of permitted upstream provider identifiers (empty = all allowed)", example = "[\"openai\", \"anthropic\"]")
		Set<String> allowedProviders
) {
	public CreateKeyRequest {
		rpmLimit = rpmLimit != null ? rpmLimit : 0;
		tpmLimit = tpmLimit != null ? tpmLimit : 0;
		allowedModels = allowedModels != null ? Set.copyOf(allowedModels) : Set.of();
		allowedProviders = allowedProviders != null ? Set.copyOf(allowedProviders) : Set.of();
	}
}
