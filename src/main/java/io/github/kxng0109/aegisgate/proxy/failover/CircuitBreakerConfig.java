package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Wiring for the Redis-backed distributed circuit breaker.
 *
 * <p>Two Lettuce factories exist side by side. The shared {@code RedisConnectionFactory} is marked {@code @Primary} so
 * Spring Boot's auto-configured {@code redisTemplate} / {@code stringRedisTemplate} keep working (Boot 4 creates them
 * only when there is a single candidate factory). The dedicated {@code circuitBreakerRedisConnectionFactory} carries a
 * short command timeout so breaker reads and writes fail fast against a slow or unavailable Redis instead of stalling
 * virtual threads; callers are expected to fall back to the in-memory {@link ProviderCircuitBreaker} mirror. Redis is
 * therefore always required and the mirror is only a fallback, so the breaker is fail-closed.</p>
 *
 * <p>The three Lua scripts implement the distributed state machine atomically on the server; the per-instance
 * {@link InstanceId} lets exactly one instance own a HALF_OPEN probe, and the bulkhead {@link Semaphore} caps how
 * many virtual threads can be waiting on Redis commands at once.</p>
 */
@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class CircuitBreakerConfig {

	private static final String DEFAULT_REDIS_HOST = "localhost";

	private static final int DEFAULT_REDIS_PORT = 6379;

	/**
	 * Creates the shared primary connection factory used by the rate limiter and the rest of the application.
	 *
	 * <p>Spring Boot manages the lettuce lifecycle ({@code afterPropertiesSet} runs as part of bean initialization), so no
	 * explicit call is needed here. The connection is opened lazily on first use.</p>
	 *
	 * @param redisProperties bound {@code spring.data.redis.*} properties
	 * @return the shared, primary connection factory
	 */
	@Bean
	@Primary
	public RedisConnectionFactory redisConnectionFactory(DataRedisProperties redisProperties) {
		RedisConfiguration configuration = redisConfiguration(redisProperties);
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder().build();
		return new LettuceConnectionFactory(configuration, clientConfiguration);
	}

	/**
	 * Creates the dedicated fast breaker connection factory with a short command timeout.
	 *
	 * <p>Not marked {@code @Primary}, so it never competes with the shared factory. {@code afterPropertiesSet} is called
	 * explicitly because the factory is returned with a plain subtyping type and must be usable immediately.</p>
	 *
	 * @param redisProperties bound {@code spring.data.redis.*} properties
	 * @param circuitBreakerProperties bound {@code gateway.circuit-breaker.*} properties
	 * @return the dedicated breaker connection factory
	 */
	@Bean("circuitBreakerRedisConnectionFactory")
	public LettuceConnectionFactory circuitBreakerRedisConnectionFactory(
			DataRedisProperties redisProperties,
			CircuitBreakerProperties circuitBreakerProperties
	) {
		RedisConfiguration configuration = redisConfiguration(redisProperties);
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
				.commandTimeout(circuitBreakerProperties.redisTimeout())
				.build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, clientConfiguration);
		factory.afterPropertiesSet();
		return factory;
	}

	/**
	 * Creates the dedicated String template bound to the fast breaker factory.
	 *
	 * @param connectionFactory the dedicated breaker factory
	 * @return the breaker template
	 */
	@Bean("circuitBreakerRedisTemplate")
	public StringRedisTemplate circuitBreakerRedisTemplate(
			@Qualifier("circuitBreakerRedisConnectionFactory") LettuceConnectionFactory connectionFactory
	) {
		return new StringRedisTemplate(connectionFactory);
	}

	/**
	 * Creates the Lua script that atomically acquires a breaker slot (CLOSED pass, HALF_OPEN probe ownership).
	 *
	 * @return the try-acquire script
	 */
	@Bean
	public DefaultRedisScript<Long> circuitTryAcquireScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("circuit_try_acquire.lua"));
		script.setResultType(Long.class);
		return script;
	}

	/**
	 * Creates the Lua script that atomically records a failure and may trip the circuit.
	 *
	 * @return the record-failure script
	 */
	@Bean
	public DefaultRedisScript<Long> circuitRecordFailureScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("circuit_record_failure.lua"));
		script.setResultType(Long.class);
		return script;
	}

	/**
	 * Creates the Lua script that atomically records a success and may close the circuit.
	 *
	 * @return the record-success script
	 */
	@Bean
	public DefaultRedisScript<Long> circuitRecordSuccessScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("circuit_record_success.lua"));
		script.setResultType(Long.class);
		return script;
	}

	/**
	 * Creates the stable per-instance id used to arbitrate HALF_OPEN probe ownership.
	 *
	 * @param environment the application environment
	 * @return the configured id, or a random UUID when {@code spring.application.instance-id} is not set
	 */
	@Bean
	public InstanceId instanceId(Environment environment) {
		String value = environment.getProperty("spring.application.instance-id");
		if (value == null || value.isBlank()) {
			return InstanceId.generate();
		}
		return new InstanceId(value);
	}

	/**
	 * Creates the bulkhead that bounds concurrent breaker Redis commands.
	 *
	 * @param circuitBreakerProperties bound {@code gateway.circuit-breaker.*} properties
	 * @return the bulkhead semaphore
	 */
	@Bean
	public Semaphore circuitBreakerBulkhead(CircuitBreakerProperties circuitBreakerProperties) {
		return new Semaphore(circuitBreakerProperties.bulkheadPermits());
	}

	/**
	 * Creates the Redis-backed breaker factory used to obtain per-provider breakers.
	 *
	 * @param breakerTemplate the dedicated fast breaker template
	 * @param circuitTryAcquireScript the try-acquire script
	 * @param circuitRecordFailureScript the record-failure script
	 * @param circuitRecordSuccessScript the record-success script
	 * @param circuitBreakerProperties bound {@code gateway.circuit-breaker.*} properties
	 * @param instanceId this instance's id
	 * @param gatewayProperties bound {@code gateway.*} properties naming the providers
	 * @param circuitBreakerBulkhead the bulkhead semaphore
	 * @return the factory
	 */
	@Bean
	public CircuitBreakerFactory circuitBreakerFactory(
			@Qualifier("circuitBreakerRedisTemplate") StringRedisTemplate breakerTemplate,
			DefaultRedisScript<Long> circuitTryAcquireScript,
			DefaultRedisScript<Long> circuitRecordFailureScript,
			DefaultRedisScript<Long> circuitRecordSuccessScript,
			CircuitBreakerProperties circuitBreakerProperties,
			InstanceId instanceId,
			GatewayProperties gatewayProperties,
			Semaphore circuitBreakerBulkhead
	) {
		return new RedisCircuitBreakerFactory(
				breakerTemplate,
				circuitTryAcquireScript,
				circuitRecordFailureScript,
				circuitRecordSuccessScript,
				circuitBreakerProperties,
				instanceId,
				gatewayProperties,
				Clock.systemUTC(),
				circuitBreakerBulkhead
		);
	}

	private static RedisConfiguration redisConfiguration(DataRedisProperties redisProperties) {
		DataRedisProperties.Sentinel sentinel = redisProperties.getSentinel();
		if (sentinel != null && StringUtils.hasText(sentinel.getMaster())) {
			Set<String> sentinelNodes = sentinel.getNodes() != null ? new HashSet<>(sentinel.getNodes()) : new HashSet<>();
			RedisSentinelConfiguration configuration = new RedisSentinelConfiguration(sentinel.getMaster(), sentinelNodes);
			applyCredentials(configuration, redisProperties.getUsername(), redisProperties.getPassword());
			applySentinelCredentials(configuration, sentinel.getUsername(), sentinel.getPassword());
			return configuration;
		}
		DataRedisProperties.Cluster cluster = redisProperties.getCluster();
		if (cluster != null && !CollectionUtils.isEmpty(cluster.getNodes())) {
			RedisClusterConfiguration configuration = new RedisClusterConfiguration(cluster.getNodes());
			applyCredentials(configuration, redisProperties.getUsername(), redisProperties.getPassword());
			return configuration;
		}
		DataRedisProperties.Masterreplica masterreplica = redisProperties.getMasterreplica();
		if (masterreplica != null && !CollectionUtils.isEmpty(masterreplica.getNodes())) {
			RedisStaticMasterReplicaConfiguration configuration = new RedisStaticMasterReplicaConfiguration();
			for (String node : masterreplica.getNodes()) {
				String[] hostAndPort = node.split(":");
				configuration.addNode(hostAndPort[0],
						hostAndPort.length > 1 ? parsePort(hostAndPort[1]) : DEFAULT_REDIS_PORT);
			}
			applyCredentials(configuration, redisProperties.getUsername(), redisProperties.getPassword());
			return configuration;
		}
		RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
		standalone.setHostName(hostOf(redisProperties));
		standalone.setPort(portOf(redisProperties));
		applyCredentials(standalone, redisProperties.getUsername(), redisProperties.getPassword());
		return standalone;
	}

	private static void applyCredentials(RedisConfiguration.WithAuthentication configuration,
			String username, String password) {
		if (username != null && !username.isBlank()) {
			configuration.setUsername(username);
		}
		if (password != null && !password.isBlank()) {
			configuration.setPassword(RedisPassword.of(password));
		}
	}

	private static void applySentinelCredentials(RedisSentinelConfiguration configuration,
			String username, String password) {
		if (username != null && !username.isBlank()) {
			configuration.setSentinelUsername(username);
		}
		if (password != null && !password.isBlank()) {
			configuration.setSentinelPassword(RedisPassword.of(password));
		}
	}

	private static int parsePort(String value) {
		try {
			int port = Integer.parseInt(value);
			return port > 0 ? port : DEFAULT_REDIS_PORT;
		} catch (NumberFormatException e) {
			return DEFAULT_REDIS_PORT;
		}
	}

	private static String hostOf(DataRedisProperties redisProperties) {
		String host = redisProperties.getHost();
		return host == null || host.isBlank() ? DEFAULT_REDIS_HOST : host;
	}

	private static int portOf(DataRedisProperties redisProperties) {
		int port = redisProperties.getPort();
		return port > 0 ? port : DEFAULT_REDIS_PORT;
	}
}