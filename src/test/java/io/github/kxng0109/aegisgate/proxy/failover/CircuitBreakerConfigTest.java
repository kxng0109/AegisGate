package io.github.kxng0109.aegisgate.proxy.failover;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the branchy helper paths in {@link CircuitBreakerConfig} that a plain context load (which binds an empty
 * {@code spring.data.redis}) never reaches: explicit host/port, credentials, Sentinel/Cluster/master-replica topology
 * selection, and the {@code instance-id} resolution.
 */
class CircuitBreakerConfigTest {

	private final CircuitBreakerConfig config = new CircuitBreakerConfig();

	@Test
	void instanceIdComesFromPropertyWhenSet() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.application.instance-id", "node-7")));
		assertThat(config.instanceId(env).value()).isEqualTo("node-7");
	}

	@Test
	void instanceIdIsGeneratedWhenPropertyMissing() {
		StandardEnvironment env = new StandardEnvironment();
		assertThat(config.instanceId(env).value()).isNotBlank();
	}

	@Test
	void instanceIdIsGeneratedWhenPropertyBlank() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.application.instance-id", "   ")));
		assertThat(config.instanceId(env).value()).isNotBlank();
	}

	@Test
	void sharedFactoryUsesDefaultsWhenPropertiesUnset() {
		RedisConnectionFactory factory = config.redisConnectionFactory(new DataRedisProperties());
		assertThat(factory).isNotNull();
	}

	@Test
	void sharedFactoryAppliesHostPortAndCredentials() {
		DataRedisProperties props = new DataRedisProperties();
		props.setHost("redis.internal");
		props.setPort(6380);
		props.setUsername("user");
		props.setPassword("secret");
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void sharedFactoryHandlesBlankHostPortAndCredentials() {
		DataRedisProperties props = new DataRedisProperties();
		props.setHost("");
		props.setPort(0);
		props.setUsername("");
		props.setPassword("");
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void sharedFactoryUsesSentinelTopology() {
		DataRedisProperties props = new DataRedisProperties();
		props.setSentinel(new DataRedisProperties.Sentinel());
		props.getSentinel().setMaster("mymaster");
		props.getSentinel().setNodes(List.of("127.0.0.1:26379"));
		LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(props);
		assertThat(factory).isNotNull();
		assertThat(factory.getSentinelConfiguration()).isNotNull();
	}

	@Test
	void sharedFactoryWiresSentinelNodeCredentials() {
		DataRedisProperties props = new DataRedisProperties();
		DataRedisProperties.Sentinel sentinel = new DataRedisProperties.Sentinel();
		sentinel.setMaster("mymaster");
		sentinel.setNodes(List.of("127.0.0.1:26379"));
		sentinel.setUsername("sentinel-user");
		sentinel.setPassword("sentinel-pass");
		props.setSentinel(sentinel);
		LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(props);
		assertThat(factory.getSentinelConfiguration()).isNotNull();
		assertThat(factory.getSentinelConfiguration().getSentinelUsername()).isEqualTo("sentinel-user");
		assertThat(factory.getSentinelConfiguration().getSentinelPassword().map(pw -> new String(pw)).orElse(""))
				.isEqualTo("sentinel-pass");
	}

	@Test
	void sharedFactoryUsesClusterTopology() {
		DataRedisProperties props = new DataRedisProperties();
		props.setCluster(new DataRedisProperties.Cluster());
		props.getCluster().setNodes(List.of("127.0.0.1:7000", "127.0.0.1:7001"));
		LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(props);
		assertThat(factory).isNotNull();
		assertThat(factory.getClusterConfiguration()).isNotNull();
	}

	@Test
	void sharedFactoryUsesMasterReplicaTopology() {
		DataRedisProperties props = new DataRedisProperties();
		props.setMasterreplica(new DataRedisProperties.Masterreplica());
		props.getMasterreplica().setNodes(List.of("127.0.0.1:6379"));
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void sharedFactoryAcceptsSentinelMasterWithoutNodes() {
		DataRedisProperties props = new DataRedisProperties();
		props.setSentinel(new DataRedisProperties.Sentinel());
		props.getSentinel().setMaster("mymaster");
		LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(props);
		assertThat(factory).isNotNull();
		assertThat(factory.getSentinelConfiguration()).isNotNull();
	}

	@Test
	void sharedFactoryDefaultsPortForMasterReplicaNodeWithoutPort() {
		DataRedisProperties props = new DataRedisProperties();
		props.setMasterreplica(new DataRedisProperties.Masterreplica());
		props.getMasterreplica().setNodes(List.of("redis-node"));
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void sharedFactoryDefaultsPortForInvalidMasterReplicaPort() {
		DataRedisProperties props = new DataRedisProperties();
		props.setMasterreplica(new DataRedisProperties.Masterreplica());
		props.getMasterreplica().setNodes(List.of("redis-node:0", "redis-node:not-a-port"));
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void sharedFactoryFallsBackToStandaloneWhenTopologiesIncomplete() {
		DataRedisProperties props = new DataRedisProperties();
		props.setSentinel(new DataRedisProperties.Sentinel());
		props.getSentinel().setMaster("");
		props.setCluster(new DataRedisProperties.Cluster());
		props.getCluster().setNodes(List.of());
		props.setMasterreplica(new DataRedisProperties.Masterreplica());
		props.getMasterreplica().setNodes(List.of());
		assertThat(config.redisConnectionFactory(props)).isNotNull();
	}

	@Test
	void scriptsAndBulkheadBeansAreConstructable() {
		assertThat(config.circuitTryAcquireScript()).isNotNull();
		assertThat(config.circuitRecordFailureScript()).isNotNull();
		assertThat(config.circuitRecordSuccessScript()).isNotNull();
		Semaphore bulkhead = config.circuitBreakerBulkhead(
				new CircuitBreakerProperties(Duration.ofMillis(250), 3, Duration.ofSeconds(30), Duration.ofSeconds(60), 256));
		assertThat(bulkhead.availablePermits()).isEqualTo(256);

		CircuitBreakerProperties cbProps = new CircuitBreakerProperties(
				Duration.ofMillis(250),
				3,
				Duration.ofSeconds(30),
				Duration.ofSeconds(60),
				256
		);
		DataRedisProperties redisProps = new DataRedisProperties();
		LettuceConnectionFactory cbFactory = config.circuitBreakerRedisConnectionFactory(redisProps, cbProps);
		assertThat(cbFactory).isNotNull();

		var template = config.circuitBreakerRedisTemplate(cbFactory);
		assertThat(template).isNotNull();

		var factory = config.circuitBreakerFactory(
				template,
				config.circuitTryAcquireScript(),
				config.circuitRecordFailureScript(),
				config.circuitRecordSuccessScript(),
				cbProps,
				InstanceId.generate(),
				new io.github.kxng0109.aegisgate.contracts.GatewayProperties(),
				bulkhead
		);
		assertThat(factory).isNotNull();
	}
}