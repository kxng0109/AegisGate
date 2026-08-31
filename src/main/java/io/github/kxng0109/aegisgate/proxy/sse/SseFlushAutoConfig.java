package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the periodic SSE flush engine.
 *
 * <p>One shared scheduler runs the 10&nbsp;ms registry scan for every connection — deliberately not one timer task per
 * connection, so the timer overhead stays constant at 1000+ requests per minute. The strategy owns that scheduler
 * internally (a single daemon virtual thread) rather than exposing it as a bean: a bare
 * {@code ScheduledExecutorService} bean would be selected by Spring as the executor behind every {@code @Scheduled}
 * task and would stall under Hikari retries. The strategy also owns the virtual-thread-per-task executor that actually
 * runs the flushes; both are closed with the context.</p>
 */
@Configuration
@EnableConfigurationProperties(SseFlushProperties.class)
public class SseFlushAutoConfig {

	/**
	 * Creates the adaptive flush strategy.
	 *
	 * @param properties    bound {@code aegisgate.sse.flush.*} properties
	 * @param meterRegistry registry the SSE metrics are registered with
	 * @return the strategy
	 */
	@Bean(destroyMethod = "close")
	public AdaptiveSseFlushStrategy sseFlushStrategy(
			SseFlushProperties properties,
			MeterRegistry meterRegistry
	) {
		return new AdaptiveSseFlushStrategy(properties, meterRegistry);
	}

	/**
	 * Creates the SSE flush health indicator.
	 *
	 * @param strategy the flush engine whose state is reported
	 * @return the health indicator
	 */
	@Bean
	public SseFlushHealthIndicator sseFlushHealthIndicator(AdaptiveSseFlushStrategy strategy) {
		return new SseFlushHealthIndicator(strategy);
	}
}