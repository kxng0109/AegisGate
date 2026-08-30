package io.github.kxng0109.aegisgate.proxy.failover;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the breaker through all three states so {@link CircuitBreakerMetrics#bindTo} exercises every branch of the
 * state-to-number encoding (CLOSED=0, OPEN=1, HALF_OPEN=2).
 */
class CircuitBreakerMetricsTest {

	static final class MutableClock extends Clock {
		private Instant now = Instant.EPOCH;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	private static final class FakeFactory implements CircuitBreakerFactory {
		private final Map<String, CircuitBreaker> breakers;

		FakeFactory(Map<String, CircuitBreaker> breakers) {
			this.breakers = breakers;
		}

		@Override
		public CircuitBreaker get(String providerName) {
			return breakers.get(providerName);
		}

		@Override
		public Set<String> providerNames() {
			return breakers.keySet();
		}

		@Override
		public void reset() {
		}

		@Override
		public Map<String, CircuitBreaker.State> states() {
			return Map.of();
		}
	}

	@Test
	void exposesStateAsNumericGaugePerProvider() {
		MutableClock clock = new MutableClock();
		ProviderCircuitBreaker closed = new ProviderCircuitBreaker("closed", clock);
		ProviderCircuitBreaker open = new ProviderCircuitBreaker("open", clock);
		for (int i = 0; i < 3; i++) {
			open.recordFailure();
		}
		ProviderCircuitBreaker half = new ProviderCircuitBreaker("half", clock);
		for (int i = 0; i < 3; i++) {
			half.recordFailure();
		}
		clock.advance(Duration.ofSeconds(31));
		assertThat(half.tryAcquire()).isTrue();

		CircuitBreakerFactory factory = new FakeFactory(Map.of("closed", closed, "open", open, "half", half));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new CircuitBreakerMetrics(factory).bindTo(registry);

		assertThat(gauge(registry, "closed")).isZero();
		assertThat(gauge(registry, "open")).isEqualTo(1.0);
		assertThat(gauge(registry, "half")).isEqualTo(2.0);
	}

	private static double gauge(SimpleMeterRegistry registry, String provider) {
		return registry.get("aegis.circuit.breaker.state").tag("provider", provider).gauge().value();
	}
}