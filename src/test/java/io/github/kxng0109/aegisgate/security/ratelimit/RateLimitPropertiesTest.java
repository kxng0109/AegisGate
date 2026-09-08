package io.github.kxng0109.aegisgate.security.ratelimit;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitProperties")
class RateLimitPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		RateLimitProperties props = RateLimitProperties.DEFAULTS;

		assertEquals(60_000L, props.windowMillis());
		assertEquals(1, props.minEstimatedTokens());
		assertEquals(1_000_000, props.maxEstimatedTokens());
		assertEquals(1_000, props.keyCacheMaximumSize());
		assertEquals(5, props.keyCacheTtlSeconds());
		assertTrue(VALIDATOR.validate(props).isEmpty());
	}

	@Test
	@DisplayName("properties bind from the gateway.ratelimit prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.ratelimit.window-millis", "30000")
				                                          .withProperty(
						                                          "gateway.ratelimit.max-estimated-tokens",
						                                          "500000"
				                                          )
				                                          .withProperty(
						                                          "gateway.ratelimit.key-cache-maximum-size",
						                                          "5000"
				                                          )
				                                          .withProperty(
						                                          "gateway.ratelimit.key-cache-ttl-seconds",
						                                          "30"
				                                          ));

		RateLimitProperties bound = Binder.get(environment)
		                                  .bind("gateway.ratelimit", RateLimitProperties.class)
		                                  .get();

		assertEquals(30_000L, bound.windowMillis());
		assertEquals(1, bound.minEstimatedTokens());
		assertEquals(500_000, bound.maxEstimatedTokens());
		assertEquals(5_000, bound.keyCacheMaximumSize());
		assertEquals(30, bound.keyCacheTtlSeconds());
		assertTrue(VALIDATOR.validate(bound).isEmpty());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.ratelimit.window-millis", "30000"));

		RateLimitProperties bound = Binder.get(environment)
		                                  .bind("gateway.ratelimit", RateLimitProperties.class)
		                                  .get();

		assertEquals(30_000L, bound.windowMillis());
		assertEquals(1, bound.minEstimatedTokens());
		assertEquals(1_000_000, bound.maxEstimatedTokens());
		assertEquals(1_000, bound.keyCacheMaximumSize());
		assertEquals(5, bound.keyCacheTtlSeconds());
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertFalse(VALIDATOR.validate(
				new RateLimitProperties(999L, 1, 1_000_000, 1_000, 5)).isEmpty());
		assertFalse(VALIDATOR.validate(
				new RateLimitProperties(3_600_001L, 1, 1_000_000, 1_000, 5)).isEmpty());
		assertFalse(VALIDATOR.validate(
				new RateLimitProperties(60_000L, 1, 999, 1_000, 5)).isEmpty());
		assertFalse(VALIDATOR.validate(
				new RateLimitProperties(60_000L, 1, 1_000_000, 99, 5)).isEmpty());
		assertFalse(VALIDATOR.validate(
				new RateLimitProperties(60_000L, 1, 1_000_000, 1_000, 0)).isEmpty());
		assertTrue(VALIDATOR.validate(
				new RateLimitProperties(1_000L, 1, 1_000, 100, 1)).isEmpty());
		assertTrue(VALIDATOR.validate(
				new RateLimitProperties(3_600_000L, 1, 100_000_000, 1_000_000, 3_600)).isEmpty());
	}
}
