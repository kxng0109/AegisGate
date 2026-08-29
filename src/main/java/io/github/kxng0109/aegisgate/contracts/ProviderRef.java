package io.github.kxng0109.aegisgate.contracts;

import org.jspecify.annotations.Nullable;

/**
 * One step in a {@link ModelAlias} provider chain: which provider to try and, optionally, a different model name to
 * send to that provider.
 *
 * @param providerName  must match the {@code name} of a configured {@link ProviderConfig} (unchecked at bind time; the
 *                      routing layer fails fast when it cannot resolve it)
 * @param modelOverride optional model remapping for this step; {@code null} means the client requested model is sent as
 *                      is
 */
public record ProviderRef(
		String providerName,
		@Nullable String modelOverride
) {
}