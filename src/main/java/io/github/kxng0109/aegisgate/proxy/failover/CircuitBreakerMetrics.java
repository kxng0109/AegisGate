package io.github.kxng0109.aegisgate.proxy.failover;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Exposes the per provider circuit breaker state as a Micrometer gauge.
 *
 * <p>One {@code aegis.circuit.breaker.state} gauge is registered per provider and tagged with the provider name. The
 * gauge value encodes {@link CircuitBreaker.State} as {@code 0} for CLOSED, {@code 1} for OPEN and {@code 2} for
 * HALF_OPEN, so a healthy provider always reads {@code 0}.</p>
 *
 * <p>Spring Boot binds every {@link MeterBinder} bean to the managed {@link MeterRegistry}, so these gauges are
 * scraped by the Prometheus endpoint and drive Grafana dashboards for failover health.</p>
 */
@Component
public class CircuitBreakerMetrics implements MeterBinder {

	private final CircuitBreakerFactory factory;

	/**
	 * Creates the metrics binder for the given factory.
	 *
	 * @param factory the circuit breaker factory whose per provider states are exposed as gauges
	 */
	public CircuitBreakerMetrics(CircuitBreakerFactory factory) {
		this.factory = factory;
	}

	/**
	 * Registers one gauge per provider.
	 *
	 * @param registry the registry the gauges are registered with
	 */
	@Override
	public void bindTo(MeterRegistry registry) {
		for (String name : factory.providerNames()) {
			Gauge.builder("aegis.circuit.breaker.state", factory, f -> stateCode(f.get(name).getState()))
					.tag("provider", name)
					.baseUnit("state")
					.register(registry);
		}
	}

	private static int stateCode(CircuitBreaker.State state) {
		return switch (state) {
			case CLOSED -> 0;
			case OPEN -> 1;
			case HALF_OPEN -> 2;
		};
	}
}