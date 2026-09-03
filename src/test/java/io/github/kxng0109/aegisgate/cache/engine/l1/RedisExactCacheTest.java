package io.github.kxng0109.aegisgate.cache.engine.l1;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RedisExactCache")
class RedisExactCacheTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private RedisExactCache cache;

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		cache = new RedisExactCache(redisTemplate, objectMapper, properties);
	}

	@Test
	@DisplayName("get and put serialize and deserialize CacheEntry successfully")
	void getAndPut() throws Exception {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash123", "", "", "Hello"
		);
		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"Hello",
				"",
				"",
				"{\"response\":\"world\"}",
				5,
				10,
				15,
				Instant.now(),
				1.0f
		);

		String json = objectMapper.writeValueAsString(entry);
		when(valueOps.get(key.toExactRedisKey())).thenReturn(json);

		CacheEntry retrieved = cache.get(key);
		assertThat(retrieved).isNotNull();
		assertThat(retrieved.promptText()).isEqualTo("Hello");
		assertThat(retrieved.totalTokens()).isEqualTo(15);

		cache.put(key, entry, Duration.ofHours(24));
		verify(valueOps).set(eq(key.toExactRedisKey()), any(), eq(Duration.ofHours(24)));

		cache.delete(key.toExactRedisKey());
		verify(redisTemplate).delete(key.toExactRedisKey());
	}

	@Test
	@DisplayName("handles disabled L1 cache, DataAccessException, and malformed json safely")
	void exceptionAndDisabledHandling() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash123", "", "", "Hello"
		);
		CacheEntry entry = new CacheEntry(
				"id1", "tenant1", CacheScope.TENANT, "gpt-4o", "Hello", "", "", "{}", 5, 10, 15, Instant.now(), 1.0f
		);

		// Disabled
		properties.getExact().setL1RedisEnabled(false);
		assertThat(cache.get(key)).isNull();
		cache.put(key, entry, Duration.ofHours(1));
		properties.getExact().setL1RedisEnabled(true);

		// Malformed JSON
		when(valueOps.get(key.toExactRedisKey())).thenReturn("not-valid-json");
		assertThat(cache.get(key)).isNull();

		// DataAccessException on get
		when(valueOps.get(key.toExactRedisKey())).thenThrow(new org.springframework.data.redis.RedisSystemException(
				"err",
				new RuntimeException()
		));
		assertThat(cache.get(key)).isNull();

		// DataAccessException on put and delete
		doThrow(new org.springframework.data.redis.RedisSystemException("err", new RuntimeException())).when(valueOps)
		                                                                                               .set(
				                                                                                               any(),
				                                                                                               any(),
				                                                                                               any(Duration.class)
		                                                                                               );
		cache.put(key, entry, Duration.ofHours(1));

		doThrow(new org.springframework.data.redis.RedisSystemException("err", new RuntimeException())).when(
				redisTemplate).delete(anyString());
		cache.delete(key.toExactRedisKey());

		// Null, zero, and negative TTLs
		cache.put(key, entry, null);
		cache.put(key, entry, Duration.ZERO);
		cache.put(key, entry, Duration.ofSeconds(-1));

		// Serialization exception
		ObjectMapper faultyMapper = mock(ObjectMapper.class);
		when(faultyMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Serialization failure"));
		RedisExactCache faultyCache = new RedisExactCache(redisTemplate, faultyMapper, properties);
		faultyCache.put(key, entry, Duration.ofHours(1));
	}
}
