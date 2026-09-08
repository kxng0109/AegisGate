package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SseCapacityProperties")
class SseCapacityPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		SseCapacityProperties props = SseCapacityProperties.DEFAULTS;

		assertEquals(10_000, props.maxConnections());
		assertEquals(10L, props.tickPeriodMs());
		assertEquals(30_000L, props.watchdogTimeoutMs());
		assertTrue(VALIDATOR.validate(props).isEmpty());
	}

	@Test
	@DisplayName("properties bind from the aegisgate.sse.capacity prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty(
						                                          "aegisgate.sse.capacity.max-connections",
						                                          "60000"
				                                          )
				                                          .withProperty("aegisgate.sse.capacity.tick-period-ms", "5")
				                                          .withProperty(
						                                          "aegisgate.sse.capacity.watchdog-timeout-ms",
						                                          "15000"
				                                          ));

		SseCapacityProperties bound = Binder.get(environment)
		                                    .bind("aegisgate.sse.capacity", SseCapacityProperties.class)
		                                    .get();

		assertEquals(60_000, bound.maxConnections());
		assertEquals(5L, bound.tickPeriodMs());
		assertEquals(15_000L, bound.watchdogTimeoutMs());
		assertTrue(VALIDATOR.validate(bound).isEmpty());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty(
						                                          "aegisgate.sse.capacity.max-connections",
						                                          "60000"
				                                          ));

		SseCapacityProperties bound = Binder.get(environment)
		                                    .bind("aegisgate.sse.capacity", SseCapacityProperties.class)
		                                    .get();

		assertEquals(60_000, bound.maxConnections());
		assertEquals(10L, bound.tickPeriodMs());
		assertEquals(30_000L, bound.watchdogTimeoutMs());
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(99, 10, 30_000L)).isEmpty());
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(1_000_001, 10, 30_000L)).isEmpty());
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(10_000, 0, 30_000L)).isEmpty());
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(10_000, 1_001, 30_000L)).isEmpty());
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(10_000, 10, 999L)).isEmpty());
		assertFalse(VALIDATOR.validate(new SseCapacityProperties(10_000, 10, 300_001L)).isEmpty());
		assertTrue(VALIDATOR.validate(new SseCapacityProperties(100, 1, 1_000L)).isEmpty());
		assertTrue(VALIDATOR.validate(new SseCapacityProperties(1_000_000, 1_000, 300_000L)).isEmpty());
	}

	@Test
	@DisplayName("strategy honors explicit capacity while the 2-arg ctor keeps defaults")
	void strategyHonorsCapacity() {
		MeterRegistry registry = new SimpleMeterRegistry();
		try (
				AdaptiveSseFlushStrategy defaults =
						new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry);
				AdaptiveSseFlushStrategy raised = new AdaptiveSseFlushStrategy(
						SseFlushProperties.DEFAULTS,
						new SseCapacityProperties(60_000, 5, 15_000L),
						registry
				)
		) {
			assertEquals(10_000, defaults.maxConnections());
			assertEquals(60_000, raised.maxConnections());
		}
	}
}
