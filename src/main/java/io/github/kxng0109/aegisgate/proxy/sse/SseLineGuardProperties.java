package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration properties for the SSE line guard.
 *
 * <p>Prefix: {@code aegisgate.sse.line-guard}. All durations are parsed as standard
 * Spring durations (e.g. {@code 30s}, {@code 5s}).</p>
 *
 * <p>The {@code perProvider} map uses {@link SseLineGuard.ProviderType} as the key
 * and {@link SseLineGuard.ProviderConfig} as the value. Spring Boot binds enum keys case-insensitively, so
 * {@code OPENAI}, {@code openai}, and {@code OpenAI} all resolve to the same constant.</p>
 */
@ConfigurationProperties(prefix = "aegisgate.sse.line-guard")
@Validated
public record SseLineGuardProperties(

		@DefaultValue("true")
		boolean enabled,

		@Min(1) @Max(1_048_576)
		@DefaultValue("16384")
		int globalDefaultBytes,

		@Min(0) @Max(100)
		@DefaultValue("10")
		int safetyMarginPercent,

		@NotNull
		@DefaultValue("REJECT_LINE_AND_CLOSE")
		SseLineGuard.Action action,

		@DefaultValue
		Map<SseLineGuard.@Valid ProviderType, SseLineGuard.ProviderConfig> perProvider,

		@NotNull
		@DefaultValue("30s")
		Duration writeTimeout,

		@NotNull
		@DefaultValue("5s")
		Duration writeTimeoutCheckInterval
) {

	public SseLineGuardProperties {
		if (globalDefaultBytes <= 0) {
			throw new IllegalArgumentException("globalDefaultBytes must be positive");
		}
		if (safetyMarginPercent < 0 || safetyMarginPercent > 100) {
			throw new IllegalArgumentException("safetyMarginPercent must be between 0 and 100");
		}
		if (action == null) {
			throw new IllegalArgumentException("action must not be null");
		}
		if (writeTimeout == null) {
			throw new IllegalArgumentException("writeTimeout must not be null");
		}
		if (writeTimeoutCheckInterval == null) {
			throw new IllegalArgumentException("writeTimeoutCheckInterval must not be null");
		}
		if (perProvider == null) {
			throw new IllegalArgumentException("perProvider must not be null");
		}
	}

	/**
	 * Default configuration with the spec-recommended values.
	 */
	public static final SseLineGuardProperties DEFAULTS = new SseLineGuardProperties(
			true,
			16384,
			10,
			SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
			Map.of(),
			Duration.ofSeconds(30),
			Duration.ofSeconds(5)
	);
}