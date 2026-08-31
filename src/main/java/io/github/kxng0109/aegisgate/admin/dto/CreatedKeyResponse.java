package io.github.kxng0109.aegisgate.admin.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Single-exposure response containing the generated plaintext key along with its public metadata.
 *
 * @param keyId            hex digest identifier of the key
 * @param key              plaintext API key (returned strictly once on creation)
 * @param keyPrefix        visible key prefix (e.g. gw-...)
 * @param ownerId          owner identifier
 * @param name             label for the key
 * @param rpmLimit         requests per minute limit
 * @param tpmLimit         tokens per minute limit
 * @param allowedModels    allowed models
 * @param allowedProviders allowed providers
 * @param enabled          whether the key is active
 * @param createdAt        creation timestamp
 */
public record CreatedKeyResponse(
		String keyId,
		String key,
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
