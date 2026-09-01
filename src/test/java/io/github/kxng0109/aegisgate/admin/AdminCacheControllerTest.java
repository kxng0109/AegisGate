package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CachePurgeResponse;
import io.github.kxng0109.aegisgate.admin.dto.CacheStatsResponse;
import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.engine.AegisCacheService;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("AdminCacheController")
class AdminCacheControllerTest {

	private final AegisCacheService cacheService = mock(AegisCacheService.class);
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
	private AdminCacheController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminCacheController(cacheService, properties, redisTemplate, vectorClient);
	}

	@Test
	@DisplayName("getCacheStats returns current cache configuration parameters")
	void getCacheStats() {
		ResponseEntity<CacheStatsResponse> response = controller.getCacheStats();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		CacheStatsResponse stats = response.getBody();
		assertThat(stats).isNotNull();
		assertThat(stats.enabled()).isTrue();
		assertThat(stats.similarityThreshold()).isEqualTo(0.90);
		assertThat(stats.embeddingModel()).isEqualTo("text-embedding-3-small");
	}

	@Test
	@DisplayName("purgeCache executes global and tenant-level purges")
	void purgeCacheOperations() {
		when(redisTemplate.keys(anyString())).thenReturn(Set.of("key1", "key2"));

		// Tenant purge
		ResponseEntity<CachePurgeResponse> tenantRes = controller.purgeCache("tenant-abc");
		assertThat(tenantRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantRes.getBody()).isNotNull();
		assertThat(tenantRes.getBody().evictedScope()).isEqualTo("tenant-abc");
		verify(cacheService, times(1)).purgeLocalCache();

		// Global purge
		ResponseEntity<CachePurgeResponse> globalRes = controller.purgeCache(null);
		assertThat(globalRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(globalRes.getBody()).isNotNull();
		assertThat(globalRes.getBody().evictedScope()).isEqualTo("ALL");
		verify(vectorClient).dropIndex(anyString(), eq(false));

		// Blank ownerId should trigger global purge
		ResponseEntity<CachePurgeResponse> blankOwnerRes = controller.purgeCache("   ");
		assertThat(blankOwnerRes.getBody().evictedScope()).isEqualTo("ALL");

		// Exception during index recreation in global purge
		doThrow(new RuntimeException("recreation err")).when(vectorClient)
		                                               .createIndexIfNotExists(anyString(), anyString(), anyInt());
		ResponseEntity<CachePurgeResponse> recreateErrRes = controller.purgeCache(null);
		assertThat(recreateErrRes.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Null and empty keys set
		when(redisTemplate.keys(anyString())).thenReturn(null);
		controller.purgeCache("tenant-nullkeys");

		when(redisTemplate.keys(anyString())).thenReturn(Set.of());
		controller.purgeCache("tenant-emptykeys");

		// Exception handling during key purge
		when(redisTemplate.keys(anyString())).thenThrow(new RuntimeException("Redis keys failure"));
		ResponseEntity<CachePurgeResponse> errorRes = controller.purgeCache("tenant-err");
		assertThat(errorRes.getStatusCode()).isEqualTo(HttpStatus.OK);
	}
}
