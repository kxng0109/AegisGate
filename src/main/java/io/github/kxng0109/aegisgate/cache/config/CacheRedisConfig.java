package io.github.kxng0109.aegisgate.cache.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Map;

/**
 * Cache-tier Redis wiring: a dedicated connection factory and template pointed at the evictable instance.
 * Accounting (rate limits, budgets, holds, dedupe, circuit breakers) stays on Boot's auto-configured primary
 * template, so a full cache tier under {@code allkeys-lru} can only evict cache and replay-hot keys.
 *
 * <p>The template is a distinct {@link CacheRedisTemplate} subtype (not a second {@code StringRedisTemplate}
 * bean), so unqualified injection points keep resolving to Boot's template with zero ambiguity; cache
 * components take it through the explicit qualifier.</p>
 */
@Configuration
public class CacheRedisConfig {

	/**
	 * @return factory for the cache-tier Redis instance (shared connection; Lettuce is thread-safe)
	 */
	@Bean
	public LettuceConnectionFactory cacheRedisConnectionFactory(CacheRedisProperties properties) {
		LettuceConnectionFactory factory =
				new LettuceConnectionFactory(properties.host(), properties.port());
		factory.afterPropertiesSet();
		return factory;
	}

	/**
	 * Isolated native connection for RediSearch module commands ({@code FT.*}).
	 *
	 * <p>Module-command traffic shares nothing with the data path: each factory owns its own native Lettuce
	 * connection, so a multiplex desync on the vector channel (integer reply misrouted to a search slot after a
	 * slow query expires) can only affect vector lookups, never cache reads/writes. One extra TCP connection per
	 * pod is trivial against {@code maxclients}.</p>
	 *
	 * @return factory holding the vector channel to the cache-tier instance
	 */
	@Bean
	public LettuceConnectionFactory vectorRedisConnectionFactory(CacheRedisProperties properties) {
		LettuceConnectionFactory factory =
				new LettuceConnectionFactory(properties.host(), properties.port());
		factory.afterPropertiesSet();
		return factory;
	}

	/**
	 * @return template for cache-tier keys (L1/L2/replay-hot)
	 */
	@Bean
	public CacheRedisTemplate cacheRedisTemplate(
			@Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory cacheRedisConnectionFactory) {
		CacheRedisTemplate template = new CacheRedisTemplate(cacheRedisConnectionFactory);
		template.afterPropertiesSet();
		return template;
	}

	/**
	 * Redis health contributor scoped to the accounting tier only.
	 *
	 * <p>Boot's {@code DataRedis{,Reactive}HealthContributorAutoConfiguration} wrap <em>every</em>
	 * {@code RedisConnectionFactory} bean into the composite {@code redis} contributor, so the best-effort
	 * cache factory would drag aggregate health DOWN whenever the evictable instance is absent or pressured —
	 * even though every cache read degrades gracefully to a miss. This bean (named exactly
	 * {@code redisHealthContributor}) makes both auto-configurations back off via their documented
	 * {@code @ConditionalOnMissingBean(name = {"redisHealthIndicator", "redisHealthContributor"})} and
	 * restores the pre-split behavior: one probe against the accounting instance.</p>
	 *
	 * @param accountingFactory the shared primary factory (accounting tier)
	 * @return composite contributor with the single accounting member
	 */
	@Bean("redisHealthContributor")
	public HealthContributor redisHealthContributor(
			@Qualifier("redisConnectionFactory") RedisConnectionFactory accountingFactory) {
		return CompositeHealthContributor.fromMap(
				Map.of("accounting", new DataRedisHealthIndicator(accountingFactory)));
	}
}
