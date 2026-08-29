package io.github.kxng0109.aegisgate.contracts;

import java.util.Set;

/**
 * A virtual API key seeded at startup from configuration (used until the deferred admin/JWT path provides full CRUD).
 * Bound from {@code gateway.bootstrap-keys}.
 *
 * @param ownerId          owner/tenant id
 * @param name             label
 * @param plaintextKey     raw key including the {@code gw-} prefix (must be a real secret in practice; here it is
 *                         config-supplied)
 * @param rpmLimit         requests-per-minute (0 = unlimited)
 * @param tpmLimit         tokens-per-minute (0 = unlimited)
 * @param allowedModels    empty means all
 * @param allowedProviders empty means all
 */
public record BootstrapKey(
		String ownerId,
		String name,
		String plaintextKey,
		int rpmLimit,
		int tpmLimit,
		Set<String> allowedModels,
		Set<String> allowedProviders
) {
}
