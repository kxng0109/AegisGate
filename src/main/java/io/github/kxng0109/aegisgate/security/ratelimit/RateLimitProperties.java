package io.github.kxng0109.aegisgate.security.ratelimit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rate-limiting and key-cache ceilings, bound from {@code gateway.ratelimit.*}.
 *
 * <p>The fixed window length, token-estimate clamps, and the key-resolution cache sizing are read
 * at startup. The {@code public static} constants on {@link RateLimitEngine} remain as the documented fallback defaults
 * mirrored here.</p>
 *
 * @param windowMillis        fixed-window length in milliseconds
 * @param minEstimatedTokens  lower clamp applied to token estimates before they reach Redis
 * @param maxEstimatedTokens  upper clamp applied to token estimates before they reach Redis
 * @param keyCacheMaximumSize maximum entries in the key-resolution local cache
 * @param keyCacheTtlSeconds  TTL in seconds for key-resolution local cache entries
 */
@ConfigurationProperties("gateway.ratelimit")
@Validated
public record RateLimitProperties(
		@Min(1_000) @Max(3_600_000) @DefaultValue("60000") long windowMillis,
		@Min(1) @Max(1_000_000) @DefaultValue("1") int minEstimatedTokens,
		@Min(1_000) @Max(100_000_000) @DefaultValue("1000000") int maxEstimatedTokens,
		@Min(100) @Max(1_000_000) @DefaultValue("1000") int keyCacheMaximumSize,
		@Min(1) @Max(3_600) @DefaultValue("5") int keyCacheTtlSeconds
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final RateLimitProperties DEFAULTS =
			new RateLimitProperties(60_000L, 1, 1_000_000, 1_000, 5);
}
