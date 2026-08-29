package io.github.kxng0109.aegisgate.contracts;

import io.github.kxng0109.aegisgate.config.SensitiveString;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration of a single upstream LLM provider, bound from {@code gateway.providers.<name>} in application
 * configuration.
 *
 * @param name           unique provider identifier (must match the {@code providerName} of the {@link ProviderRef}s
 *                       that reference it)
 * @param type           API dialect spoken by this provider
 * @param baseUrl        root URL of the provider API
 * @param apiKey         provider credential, masked by {@link SensitiveString}
 * @param connectTimeout how long to wait for the TCP/TLS connection before giving up
 * @param requestTimeout how long to wait for the first byte of the response before giving up (this is the per-attempt
 *                       failover bound; it does not limit the duration of a long-lived SSE stream)
 */
public record ProviderConfig(
		String name,
		ProviderType type,
		URI baseUrl,
		SensitiveString apiKey,
		Duration connectTimeout,
		Duration requestTimeout
) {
}