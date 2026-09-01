package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "CreatedKeyResponse", description = "Single-exposure response containing plaintext virtual key and registered metadata")
public record CreatedKeyResponse(
		@Schema(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
		String keyId,

		@Schema(description = "Plaintext virtual API key (returned only once)", example = "gw-aB3_x9...32chars")
		String key,

		@Schema(description = "Visible key prefix", example = "gw-")
		String keyPrefix,

		@Schema(description = "Owner tenant identifier", example = "tenant-corp")
		String ownerId,

		@Schema(description = "Key label", example = "production-key")
		String name,

		@Schema(description = "Requests per minute limit", example = "120")
		int rpmLimit,

		@Schema(description = "Tokens per minute limit", example = "500000")
		int tpmLimit,

		@Schema(description = "Allowed model aliases", example = "[\"gpt-56-luna\"]")
		Set<String> allowedModels,

		@Schema(description = "Allowed upstream providers", example = "[\"openai\"]")
		Set<String> allowedProviders,

		@Schema(description = "Whether key is enabled", example = "true")
		boolean enabled,

		@Schema(description = "Creation timestamp (ISO-8601)", example = "2026-09-01T12:00:00Z")
		Instant createdAt
) {
}
