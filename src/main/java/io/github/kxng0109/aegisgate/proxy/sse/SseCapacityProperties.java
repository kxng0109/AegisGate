package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-only capacity ceilings for the SSE flush engine, bound from {@code aegisgate.sse.capacity.*}.
 *
 * <p>Unlike {@link SseFlushProperties} (hot-reloadable flush tuning), these values are read once at
 * construction: the connection-gate {@link java.util.concurrent.Semaphore} size, the registry-scan scheduler period,
 * and the watchdog timeout cannot change safely underneath live streams. A restart (or new deployment with the
 * high-throughput profile) is required to apply different values.</p>
 *
 * @param maxConnections    hard ceiling on concurrent SSE streams; excess attempts fail fast
 * @param tickPeriodMs      shared registry-scan period in milliseconds
 * @param watchdogTimeoutMs a flush blocked longer than this is killed by the per-connection watchdog
 */
@ConfigurationProperties("aegisgate.sse.capacity")
@Validated
public record SseCapacityProperties(
		@Min(100) @Max(1_000_000) @DefaultValue("10000") int maxConnections,
		@Min(1) @Max(1_000) @DefaultValue("10") long tickPeriodMs,
		@Min(1_000) @Max(300_000) @DefaultValue("30000") long watchdogTimeoutMs
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final SseCapacityProperties DEFAULTS = new SseCapacityProperties(10_000, 10, 30_000L);
}
