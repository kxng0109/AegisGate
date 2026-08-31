package io.github.kxng0109.aegisgate.admin.dto;

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
public record CreateKeyRequest(
		@NotBlank(message = "ownerId must not be blank")
		String ownerId,

		@NotBlank(message = "name must not be blank")
		String name,

		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		Set<String> allowedModels,
		Set<String> allowedProviders
) {
	public CreateKeyRequest {
		rpmLimit = rpmLimit != null ? rpmLimit : 0;
		tpmLimit = tpmLimit != null ? tpmLimit : 0;
		allowedModels = allowedModels != null ? Set.copyOf(allowedModels) : Set.of();
		allowedProviders = allowedProviders != null ? Set.copyOf(allowedProviders) : Set.of();
	}
}
