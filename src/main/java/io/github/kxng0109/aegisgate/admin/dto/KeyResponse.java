package io.github.kxng0109.aegisgate.admin.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Safe public representation of a virtual API key without plaintext secret.
 *
 * @param keyId            hex digest identifier of the key
 * @param keyPrefix        visible key prefix
 * @param ownerId          owner identifier
 * @param name             label for the key
 * @param rpmLimit         requests per minute limit
 * @param tpmLimit         tokens per minute limit
 * @param allowedModels    allowed models
 * @param allowedProviders allowed providers
 * @param enabled          whether the key is active
 * @param createdAt        creation timestamp
 */
public record KeyResponse(
		String keyId,
		String keyPrefix,
		String ownerId,
		String name,
		int rpmLimit,
		int tpmLimit,
		Set<String> allowedModels,
		Set<String> allowedProviders,
		boolean enabled,
		Instant createdAt
) {
}
