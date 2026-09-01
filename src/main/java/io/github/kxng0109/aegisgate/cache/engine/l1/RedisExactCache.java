package io.github.kxng0109.aegisgate.cache.engine.l1;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * L1 Distributed Exact Match Cache backed by Redis Strings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisExactCache {

	private final StringRedisTemplate stringRedisTemplate;
	private final ObjectMapper objectMapper;
	private final AegisCacheProperties properties;

	/**
	 * Retrieves an exact match cached entry from Redis.
	 *
	 * @param key compound cache key
	 * @return cached entry or null if absent or Redis unavailable
	 */
	public @Nullable CacheEntry get(CompoundCacheKey key) {
		if (!properties.getExact().isL1RedisEnabled()) {
			return null;
		}
		String redisKey = key.toExactRedisKey();
		try {
			String json = stringRedisTemplate.opsForValue().get(redisKey);
			if (json == null || json.isBlank()) {
				return null;
			}
			return objectMapper.readValue(json, CacheEntry.class);
		} catch (DataAccessException ex) {
			log.warn("L1 Redis cache read failed non-fatally for '{}': {}", redisKey, ex.getMessage());
			return null;
		} catch (Exception ex) {
			log.warn("Failed to deserialize L1 cache entry for '{}': {}", redisKey, ex.getMessage());
			return null;
		}
	}

	/**
	 * Stores an exact match completion entry in Redis.
	 *
	 * @param key   compound cache key
	 * @param entry cached entry
	 * @param ttl   time-to-live duration
	 */
	public void put(CompoundCacheKey key, CacheEntry entry, Duration ttl) {
		if (!properties.getExact().isL1RedisEnabled()) {
			return;
		}
		String redisKey = key.toExactRedisKey();
		try {
			String json = objectMapper.writeValueAsString(entry);
			if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
				stringRedisTemplate.opsForValue().set(redisKey, json, ttl);
			} else {
				stringRedisTemplate.opsForValue().set(redisKey, json);
			}
		} catch (DataAccessException ex) {
			log.warn("L1 Redis cache write failed non-fatally for '{}': {}", redisKey, ex.getMessage());
		} catch (Exception ex) {
			log.warn("Failed to serialize L1 cache entry for '{}': {}", redisKey, ex.getMessage());
		}
	}

	/**
	 * Deletes an exact match key from Redis.
	 *
	 * @param redisKey exact redis key
	 */
	public void delete(String redisKey) {
		try {
			stringRedisTemplate.delete(redisKey);
		} catch (DataAccessException ex) {
			log.warn("Failed to delete L1 Redis cache key '{}': {}", redisKey, ex.getMessage());
		}
	}
}
