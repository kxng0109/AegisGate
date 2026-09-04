package io.github.kxng0109.aegisgate.contracts;

import java.time.Instant;
import java.util.Set;

/**
 * A gateway-managed virtual API key, resolved from its {@link SHA256Hash}.
 *
 * <p>Stored in Redis as a hash under {@code apikey:{hashHex}}; never persisted
 * in plaintext. {@code rpmLimit}/{@code tpmLimit} of 0 mean unlimited.</p>
 *
 * @param keyHash          SHA-256 of the plaintext key (Redis key)
 * @param keyPrefix        human-visible prefix, e.g. {@code gw-}
 * @param ownerId          tenant/owner identifier used for ledger attribution
 * @param name             human-readable label
 * @param rpmLimit         requests-per-minute limit (0 = unlimited)
 * @param tpmLimit         tokens-per-minute limit (0 = unlimited)
 * @param allowedModels    empty set means "all models allowed"
 * @param allowedProviders empty set means "all providers allowed"
 * @param enabled          whether the key is currently active
 * @param createdAt        creation timestamp
 */
public record VirtualApiKey(
		SHA256Hash keyHash,
		String keyPrefix,
		String ownerId,
		String name,
		int rpmLimit,
		int tpmLimit,
		Set<String> allowedModels,
		Set<String> allowedProviders,
		Set<String> allowedTools,
		Set<String> deniedTools,
		boolean enabled,
		Instant createdAt
) {
	/**
	 * Stores immutable copies of the allow and deny lists so callers cannot mutate the
	 * key metadata after it is resolved.
	 */
	public VirtualApiKey {
		allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
		allowedProviders = allowedProviders == null ? Set.of() : Set.copyOf(allowedProviders);
		allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
		deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
	}

	/**
	 * Backwards-compatible constructor omitting tool-level RBAC/ABAC sets.
	 */
	public VirtualApiKey(
			SHA256Hash keyHash,
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
				keyHash,
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
