package io.github.kxng0109.aegisgate.mcp.resilience;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.router.McpCatalogCache;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Server Circuit Breaker Manager Unit Tests")
class McpServerCircuitBreakerManagerTest {

	private McpGatewayProperties properties;

	@Mock
	private McpCatalogCache catalogCache;

	private MutableClock clock;
	private McpServerCircuitBreakerManager manager;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		properties.setCircuitBreakerFailureThreshold(3);
		properties.setCircuitBreakerCooldown(Duration.ofSeconds(30));

		clock = new MutableClock(Instant.now());
		manager = new McpServerCircuitBreakerManager(properties, catalogCache, clock);
	}

	@Test
	@DisplayName("Manages lifecycle transitions, failure counts, and cache invalidation on trip")
	void circuitBreakerTransitions() {
		String server = "postgres";

		// 1. Initial State: CLOSED
		assertThat(manager.tryAcquire(server)).isTrue();
		ProviderCircuitBreaker breaker = manager.getBreaker(server);
		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.CLOSED);

		// 2. Consecutive failures below threshold
		manager.recordFailure(server);
		assertThat(breaker.getFailureCount()).isEqualTo(1);
		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
		assertThat(manager.tryAcquire(server)).isTrue();

		// Success resets count
		manager.recordSuccess(server);
		assertThat(breaker.getFailureCount()).isEqualTo(0);

		// 3. Trip to OPEN on 3 consecutive failures
		manager.recordFailure(server);
		manager.recordFailure(server);
		manager.recordFailure(server);

		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.OPEN);
		assertThat(manager.tryAcquire(server)).isFalse();
		verify(catalogCache, atLeastOnce()).invalidate();

		// 4. Cooldown elapsed -> probe allowed in HALF_OPEN
		clock.advance(Duration.ofSeconds(31));
		assertThat(manager.tryAcquire(server)).isTrue();
		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.HALF_OPEN);

		// Concurrent probe blocked
		assertThat(manager.tryAcquire(server)).isFalse();

		// Probe success -> CLOSED
		manager.recordSuccess(server);
		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
		assertThat(manager.tryAcquire(server)).isTrue();

		// 5. Reset
		manager.recordFailure(server);
		manager.reset(server);
		assertThat(breaker.getState()).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
		assertThat(breaker.getFailureCount()).isEqualTo(0);
	}

	private static final class MutableClock extends Clock {
		private Instant current;

		MutableClock(Instant start) {
			this.current = start;
		}

		void advance(Duration duration) {
			this.current = this.current.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
