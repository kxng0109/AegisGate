package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.config.SensitiveString;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link RedisCircuitBreakerFactory} lifecycle branches (pre-population, {@code get}, {@code providerNames},
 * {@code states}, {@code reset}) without a live Redis by using a no-op template.
 */
class RedisCircuitBreakerFactoryTest {

	static final class NoOpTemplate extends StringRedisTemplate {
		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			return null;
		}

		@Override
		public Boolean delete(String key) {
			return Boolean.TRUE;
		}
	}

	private static ProviderConfig sampleProvider(String name) {
		return new ProviderConfig(
				name,
				ProviderType.OPENAI,
				URI.create("https://api.openai.com"),
				new SensitiveString("k"),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30));
	}

	private static DefaultRedisScript<Long> script() {
		return new DefaultRedisScript<>();
	}

	@Test
	void buildsBreakersForConfiguredProvidersAndReportsState() {
		GatewayProperties properties = new GatewayProperties();
		properties.setProviders(Map.of(
				"openai", sampleProvider("openai"),
				"anthropic", sampleProvider("anthropic")));
		RedisCircuitBreakerFactory factory = new RedisCircuitBreakerFactory(
				new NoOpTemplate(),
				script(), script(), script(),
				new CircuitBreakerProperties(Duration.ofMillis(250), 3, Duration.ofSeconds(30), Duration.ofSeconds(60), 256),
				InstanceId.generate(),
				properties,
				Clock.systemUTC(),
				new Semaphore(256));

		assertThat(factory.providerNames()).containsExactlyInAnyOrder("openai", "anthropic");
		CircuitBreaker openai = factory.get("openai");
		assertThat(openai.getProviderName()).isEqualTo("openai");
		assertThat(openai.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(openai.getFailureCount()).isZero();
		assertThat(factory.states()).containsKeys("openai", "anthropic");

		factory.reset();
		assertThat(factory.get("openai")).isNotNull();
	}
}