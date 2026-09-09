package io.github.kxng0109.aegisgate.contracts;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param embeddingSingleAsString whether the OpenAI embeddings adapter serializes a single-text
 *                       batch as a bare string instead of a one-element array. Some providers
 *                       REQUIRE arrays while string-only providers REJECT them, so no single wire
 *                       shape fits all targets; keep the default array form unless the provider
 *                       is string-only (default false)
 */
public record ProviderConfig(
		String name,
		ProviderType type,
		URI baseUrl,
		SensitiveString apiKey,
		Duration connectTimeout,
		Duration requestTimeout,
		Boolean embeddingSingleAsString
) {
	/**
	 * Canonical constructor, designated for configuration binding. Null components read as absent (the
	 * {@link #isEmbeddingSingleAsString()} accessor normalizes).
	 */
	@ConstructorBinding
	public ProviderConfig(
			String name,
			ProviderType type,
			URI baseUrl,
			SensitiveString apiKey,
			Duration connectTimeout,
			Duration requestTimeout,
			Boolean embeddingSingleAsString
	) {
		this.name = name;
		this.type = type;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.connectTimeout = connectTimeout;
		this.requestTimeout = requestTimeout;
		this.embeddingSingleAsString = embeddingSingleAsString;
	}

	/**
	 * Backwards-compatible constructor defaulting {@code embeddingSingleAsString} to false.
	 */
	public ProviderConfig(
			String name,
			ProviderType type,
			URI baseUrl,
			SensitiveString apiKey,
			Duration connectTimeout,
			Duration requestTimeout
	) {
		this(name, type, baseUrl, apiKey, connectTimeout, requestTimeout, false);
	}

	/**
	 * Whether single-text embedding batches serialize as a bare string for this provider. Null-safe: a null component
	 * (e.g. legacy YAML without the key) reads as false.
	 *
	 * @return true when string-only, false for the default array form
	 */
	public boolean isEmbeddingSingleAsString() {
		return Boolean.TRUE.equals(embeddingSingleAsString);
	}
}