package io.github.kxng0109.aegisgate.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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

		@NotBlank(message = "Configuration Base Url required!")
		@Pattern(regexp = "^(http|https)://.*", message = "URL must start with http:// or https://")
		String baseUrl,

		SensitiveString apiKey,

		Duration connectTimeout,

		Duration requestTimeout,

		String chatCompletionsPath
) {

	public UpstreamConfig {
		connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(60);
		requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(60);
		chatCompletionsPath = chatCompletionsPath != null ? chatCompletionsPath : "/v1/chat/completions/";

		if (name.isBlank()) {
			throw new IllegalStateException("Configuration Name required!");
		}

		if (baseUrl.isBlank()) {
			throw new IllegalStateException("Configuration Base Url required!");
		}

		if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
			throw new IllegalStateException("URL must start with http:// or https://");
		}

		if (apiKey.value().isBlank()) {
			throw new IllegalStateException("API Key is required!");
		}

		if (connectTimeout.isNegative()) {
			throw new IllegalArgumentException("connectTimeout cannot be negative!");
		}

		if (requestTimeout.isNegative()) {
			throw new IllegalArgumentException("requestTimeout cannot be negative!");
		}

		if (!chatCompletionsPath.startsWith("/")) {
			throw new IllegalStateException("chatCompletionsPath must start with '/'");
		}

		if (chatCompletionsPath.isBlank()) {
			throw new IllegalStateException("chatCompletionsPath must not be blank!");
		}
	}
}
