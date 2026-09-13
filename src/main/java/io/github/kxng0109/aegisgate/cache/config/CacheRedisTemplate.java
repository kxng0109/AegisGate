package io.github.kxng0109.aegisgate.cache.config;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache-tier template: a distinct subtype so Spring never confuses it with Boot's accounting template during
 * type-based injection. Unqualified {@code StringRedisTemplate} points keep resolving to Boot's bean; cache
 * components take this bean through its explicit {@code @Qualifier("cacheRedisTemplate")}.
 */
public class CacheRedisTemplate extends StringRedisTemplate {

	public CacheRedisTemplate(RedisConnectionFactory connectionFactory) {
		super(connectionFactory);
	}
}
