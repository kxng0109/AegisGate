package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SseFlushProperties}: the documented defaults, relaxed binding from the
 * {@code aegisgate.sse.flush} prefix, and the {@code @Min}/{@code @Max} constraints.
 */
@DisplayName("SseFlushProperties")
class SseFlushPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		SseFlushProperties props = SseFlushProperties.DEFAULTS;

		assertEquals(16, props.maxLinesPerFlush());
		assertEquals(100, props.maxIntervalMs());
		assertEquals(500, props.flushBackpressureThresholdMs());
		assertEquals(65_536, props.maxBufferBytes());
		assertEquals(1_000, props.maxFlushesPerSecond());
		assertTrue(props.enabled());
	}

	@Test
	@DisplayName("properties bind from the aegisgate.sse.flush prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("aegisgate.sse.flush.max-lines-per-flush", "32")
				                                          .withProperty("aegisgate.sse.flush.max-interval-ms", "250")
				                                          .withProperty("aegisgate.sse.flush.enabled", "false"));

		SseFlushProperties bound = Binder.get(environment)
		                                 .bind("aegisgate.sse.flush", SseFlushProperties.class)
		                                 .get();

		assertEquals(32, bound.maxLinesPerFlush());
		assertEquals(250, bound.maxIntervalMs());
		assertEquals(500, bound.flushBackpressureThresholdMs());
		assertEquals(65_536, bound.maxBufferBytes());
		assertEquals(1_000, bound.maxFlushesPerSecond());
		assertFalse(bound.enabled());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty(
						                                          "aegisgate.sse.flush.max-lines-per-flush",
						                                          "48"
				                                          ));

		SseFlushProperties bound = Binder.get(environment)
		                                 .bind("aegisgate.sse.flush", SseFlushProperties.class)
		                                 .get();

		assertEquals(48, bound.maxLinesPerFlush());
		assertEquals(100, bound.maxIntervalMs());
		assertEquals(500, bound.flushBackpressureThresholdMs());
		assertEquals(65_536, bound.maxBufferBytes());
		assertEquals(1_000, bound.maxFlushesPerSecond());
		assertTrue(bound.enabled());
	}

	@Test
	@DisplayName("a missing prefix falls back to the defaults constant")
	void missingPrefixFallsBackToDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test"));

		SseFlushProperties bound = Binder.get(environment)
		                                 .bind("aegisgate.sse.flush", SseFlushProperties.class)
		                                 .orElse(SseFlushProperties.DEFAULTS);

		assertEquals(SseFlushProperties.DEFAULTS, bound);
	}

	@Test
	@DisplayName("out-of-range values are rejected by the constraints")
	void outOfRangeValuesAreRejected() {
		assertViolated(new SseFlushProperties(0, 100, 500, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(10_001, 100, 500, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(16, 9, 500, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(16, 5_001, 500, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(16, 100, 99, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(16, 100, 60_001, 65_536, 1_000, true));
		assertViolated(new SseFlushProperties(16, 100, 500, 1_023, 1_000, true));
		assertViolated(new SseFlushProperties(16, 100, 500, 1_048_577, 1_000, true));
		assertViolated(new SseFlushProperties(16, 100, 500, 65_536, 99, true));
		assertViolated(new SseFlushProperties(16, 100, 500, 65_536, 10_001, true));
	}

	@Test
	@DisplayName("boundary values satisfy the constraints")
	void boundaryValuesAreAccepted() {
		assertValid(new SseFlushProperties(1, 10, 100, 1_024, 100, true));
		assertValid(new SseFlushProperties(10_000, 5_000, 60_000, 1_048_576, 10_000, true));
	}

	private static void assertViolated(SseFlushProperties props) {
		Set<ConstraintViolation<SseFlushProperties>> violations = VALIDATOR.validate(props);
		assertFalse(violations.isEmpty(), "expected constraint violations for " + props);
	}

	private static void assertValid(SseFlushProperties props) {
		Set<ConstraintViolation<SseFlushProperties>> violations = VALIDATOR.validate(props);
		assertTrue(violations.isEmpty(), "expected no constraint violations for " + props + " but got " + violations);
	}
}