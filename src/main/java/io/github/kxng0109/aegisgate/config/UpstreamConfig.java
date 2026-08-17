package io.github.kxng0109.aegisgate.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for a single upstream LLM provider the gateway routes to.
 *
 * <p>Bound from the {@code gateway} prefix in {@code application.properties}
 * (e.g. {@code gateway.base-url}). Values are validated at binding time via
 * {@link Validated}, so an invalid configuration fails fast at startup rather
 * than failing silently on first use.</p>
 *
 * @param name    non-blank identifier for this upstream configuration
 * @param baseUrl http(s) URL of the provider's API root
 * @param apiKey  provider API key, masked in its {@code toString()} so it
 *                never appears in logs
 */
@Validated
@ConfigurationProperties("gateway")
public record UpstreamConfig(
		@NotBlank(message = "Configuration Name required!")
		String name,

		@NotBlank(message = "Configuration base url required")
		@Pattern(regexp = "^(http|https)://.*", message = "URL must start with http:// or https://")
		String baseUrl,

		SensitiveString apiKey
) {
}
