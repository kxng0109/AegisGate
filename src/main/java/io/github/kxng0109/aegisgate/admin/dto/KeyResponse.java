package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param allowedTools     allowed tools
 * @param deniedTools      denied tools
 * @param enabled          whether the key is active
 * @param createdAt        creation timestamp
 */
@Schema(name = "KeyResponse", description = "Safe public metadata representation of a registered virtual API key")
public record KeyResponse(
		@Schema(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
		String keyId,

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

		@Schema(description = "Allowed MCP tools", example = "[\"postgres__*\"]")
		Set<String> allowedTools,

		@Schema(description = "Denied MCP tools", example = "[\"*:delete_*\"]")
		Set<String> deniedTools,

		@Schema(description = "Whether key is enabled", example = "true")
		boolean enabled,

		@Schema(description = "Creation timestamp (ISO-8601)", example = "2026-09-01T12:00:00Z")
		Instant createdAt
) {
	public KeyResponse(
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
		this(
				keyId,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				Set.of(),
				Set.of(),
				enabled,
				createdAt
		);
	}
}
