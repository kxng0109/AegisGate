package io.github.kxng0109.aegisgate.proxy.sse;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Exposes the SSE flush health signals on the actuator health endpoint.
 *
 * <p>{@code sse.flush.lag.max_ms} is the maximum observed delay between a connection becoming flush-due (detected by
 * the shared timer) and the flush actually being issued; {@code sse.backpressure.active_connections} counts streams
 * currently blocked inside a flush. Both stay {@code 0} on a healthy gateway and are the first numbers to move when a
 * downstream population stops reading.</p>
 */
public class SseFlushHealthIndicator implements HealthIndicator {

	private final AdaptiveSseFlushStrategy strategy;

	/**
	 * Creates the indicator for the given strategy.
	 *
	 * @param strategy the flush engine whose state is reported
	 */
	public SseFlushHealthIndicator(AdaptiveSseFlushStrategy strategy) {
		this.strategy = strategy;
	}

	@Override
	public Health health() {
		return Health.up()
		             .withDetail("sse.flush.lag.max_ms", strategy.maxFlushLagMs())
		             .withDetail("sse.backpressure.active_connections", strategy.backpressureActiveConnections())
		             .build();
	}
}