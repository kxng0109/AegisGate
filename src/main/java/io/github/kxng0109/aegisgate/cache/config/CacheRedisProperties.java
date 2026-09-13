package io.github.kxng0109.aegisgate.cache.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cache-tier Redis connection details, bound from {@code gateway.redis.cache.*}.
 *
 * <p>The cache tier (L1 exact, L2 semantic vectors, replay hot tier) lives on a separate Redis instance with an
 * eviction policy, so cache pressure can never evict spend-accounting keys. The accounting tier stays on Boot's
 * standard {@code spring.data.redis.*} namespace; this tier is wired explicitly via the
 * {@code cacheRedisTemplate} / {@code cacheRedisConnectionFactory} beans.</p>
 *
 * @param host cache-tier Redis host
 * @param port cache-tier Redis port
 */
@ConfigurationProperties("gateway.redis.cache")
@Validated
public record CacheRedisProperties(
		@DefaultValue("localhost") String host,
		@Min(1) @Max(65_535) @DefaultValue("6380") int port
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final CacheRedisProperties DEFAULTS = new CacheRedisProperties("localhost", 6380);
}
