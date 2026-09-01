package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CachePurgeResponse;
import io.github.kxng0109.aegisgate.admin.dto.CacheStatsResponse;
import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.engine.AegisCacheService;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * REST controller for administrative management and inspection of the multi-tier semantic cache under
 * {@code /v1/admin/cache}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/cache")
@RequiredArgsConstructor
public class AdminCacheController {

	private final AegisCacheService cacheService;
	private final AegisCacheProperties properties;
	private final StringRedisTemplate stringRedisTemplate;
	private final RediSearchVectorClient vectorClient;

	/**
	 * Returns active cache configuration and status metrics.
	 *
	 * @return HTTP 200 OK with cache statistics
	 */
	@GetMapping("/stats")
	public ResponseEntity<CacheStatsResponse> getCacheStats() {
		CacheStatsResponse response = new CacheStatsResponse(
				properties.isEnabled(),
				properties.getDefaultScope().name(),
				properties.getSemantic().getSimilarityThreshold(),
				properties.getSemantic().getEmbeddingModel(),
				properties.getExact().getL0InMemorySize(),
				properties.getExact().getL0InMemoryTtl().toSeconds(),
				properties.getExact().isL1RedisEnabled(),
				properties.getSemantic().isEnabled(),
				properties.getSemantic().isPolarityGuardEnabled(),
				properties.getSemantic().isEntityGuardEnabled()
		);
		return ResponseEntity.ok(response);
	}

	/**
	 * Purges cached entries globally or for a specific tenant.
	 *
	 * @param ownerId optional tenant identifier to restrict the purge
	 * @return HTTP 200 OK with purge confirmation
	 */
	@DeleteMapping
	public ResponseEntity<CachePurgeResponse> purgeCache(
			@RequestParam(name = "ownerId", required = false) @Nullable String ownerId
	) {
		cacheService.purgeLocalCache();

		if (ownerId != null && !ownerId.isBlank()) {
			purgeKeysByPattern("aegis:cache:exact:" + ownerId + ":*");
			purgeKeysByPattern("aegis:cache:doc:" + ownerId + ":*");
			log.info("Administrative cache purge executed for tenant '{}'", ownerId);
			return ResponseEntity.ok(new CachePurgeResponse(true, "Purged cache for tenant " + ownerId, ownerId));
		}

		purgeKeysByPattern("aegis:cache:exact:*");
		purgeKeysByPattern("aegis:cache:doc:*");
		try {
			vectorClient.dropIndex(RedisSemanticVectorCache.INDEX_NAME, false);
			vectorClient.createIndexIfNotExists(
					RedisSemanticVectorCache.INDEX_NAME,
					RedisSemanticVectorCache.PREFIX,
					1536
			);
		} catch (Exception ex) {
			log.debug("Index re-creation notice: {}", ex.getMessage());
		}

		log.info("Global administrative cache purge executed successfully");
		return ResponseEntity.ok(new CachePurgeResponse(true, "Global cache purge completed successfully", "ALL"));
	}

	private void purgeKeysByPattern(String pattern) {
		try {
			Set<String> keys = stringRedisTemplate.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				stringRedisTemplate.delete(keys);
			}
		} catch (Exception ex) {
			log.warn("Failed to purge keys with pattern '{}': {}", pattern, ex.getMessage());
		}
	}
}
