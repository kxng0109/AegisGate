package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CachePurgeResponse;
import io.github.kxng0109.aegisgate.admin.dto.CacheStatsResponse;
import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.engine.AegisCacheService;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin - Cache Control", description = "Inspecting multi-tier cache telemetry and executing tenant-level or global cache purges")
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
	@Operation(
			summary = "Inspect multi-tier cache configuration and status",
			description = "Retrieves active cache layer statuses (L0, L1, L2), similarity threshold settings, configured embedding model alias, and guardrail flags.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Cache telemetry and configuration retrieved",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CacheStatsResponse.class),
							examples = @ExampleObject(
									name = "Cache Telemetry Response",
									value = """
											{
											  "enabled": true,
											  "defaultScope": "TENANT",
											  "similarityThreshold": 0.90,
											  "embeddingModel": "text-embedding-3-small",
											  "l0InMemorySize": 50000,
											  "l0InMemoryTtlSeconds": 60,
											  "l1RedisEnabled": true,
											  "l2SemanticEnabled": true,
											  "polarityGuardEnabled": true,
											  "entityGuardEnabled": true
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
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
	@Operation(
			summary = "Purge cache entries globally or by tenant",
			description = "Evicts local Caffeine memory cache entries, deletes Redis exact match keys, and resets/clears RediSearch vector documents. If `ownerId` is provided, only that tenant's keys are evicted.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Cache purge operation completed successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CachePurgeResponse.class),
							examples = @ExampleObject(
									name = "Purge Response",
									value = """
											{
											  "success": true,
											  "message": "Purged cache for tenant tenant-corp",
											  "evictedScope": "tenant-corp"
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@DeleteMapping
	public ResponseEntity<CachePurgeResponse> purgeCache(
			@Parameter(description = "Optional tenant owner ID to restrict purge. If omitted, triggers global purge.", example = "tenant-corp")
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

