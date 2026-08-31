package io.github.kxng0109.aegisgate.proxy.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Spring wiring of the flush engine: the bound properties, the shared ticker, the strategy bean, the
 * health indicator aggregated into the actuator health registry, and the hot-reload bridge.
 */
@SpringBootTest
@DisplayName("SseFlushAutoConfig wiring")
class SseFlushAutoConfigTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("the flush properties bind from application.yml with the documented defaults")
	void propertiesBindFromApplicationYml() {
		SseFlushProperties properties = context.getBean(SseFlushProperties.class);

		assertNotNull(properties);
		assertEquals(16, properties.maxLinesPerFlush());
		assertEquals(100, properties.maxIntervalMs());
		assertEquals(500, properties.flushBackpressureThresholdMs());
		assertEquals(65_536, properties.maxBufferBytes());
		assertEquals(1_000, properties.maxFlushesPerSecond());
		assertTrue(properties.enabled());
	}

	@Test
	@DisplayName("the strategy, health indicator, and reloader beans are registered")
	void engineBeansAreRegistered() {
		assertInstanceOf(AdaptiveSseFlushStrategy.class, context.getBean("sseFlushStrategy"));
		assertNotNull(context.getBean(SseFlushHealthIndicator.class));
		assertNotNull(context.getBean(SseFlushConfigReloader.class));
	}

	@Test
	@DisplayName("the health indicator is aggregated into the actuator health registry")
	void healthIndicatorIsAggregated() {
		HealthContributorRegistry registry = context.getBean(HealthContributorRegistry.class);

		assertTrue(
				registry.stream().anyMatch(entry -> entry.name().contains("sseFlush")),
				"the SSE flush health indicator must be registered with the actuator"
		);
	}

	@Test
	@DisplayName("the health indicator reports UP with the flush details")
	void healthIndicatorReportsUp() {
		Health health = context.getBean(SseFlushHealthIndicator.class).health();

		assertNotNull(health);
		assertEquals("UP", health.getStatus().getCode());
		assertTrue(health.getDetails().containsKey("sse.flush.lag.max_ms"));
		assertTrue(health.getDetails().containsKey("sse.backpressure.active_connections"));
	}
}