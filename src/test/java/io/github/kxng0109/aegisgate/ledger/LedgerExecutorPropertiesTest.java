package io.github.kxng0109.aegisgate.ledger;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LedgerExecutorProperties")
class LedgerExecutorPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		LedgerExecutorProperties props = LedgerExecutorProperties.DEFAULTS;

		assertEquals(2, props.corePoolSize());
		assertEquals(4, props.maxPoolSize());
		assertEquals(1_000, props.queueCapacity());
		assertEquals(10, props.awaitTerminationSeconds());
		assertTrue(VALIDATOR.validate(props).isEmpty());
	}

	@Test
	@DisplayName("properties bind from the gateway.ledger.executor prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.ledger.executor.core-pool-size", "8")
				                                          .withProperty("gateway.ledger.executor.max-pool-size", "16")
				                                          .withProperty(
						                                          "gateway.ledger.executor.queue-capacity",
						                                          "10000"
				                                          )
				                                          .withProperty(
						                                          "gateway.ledger.executor.await-termination-seconds",
						                                          "30"
				                                          ));

		LedgerExecutorProperties bound = Binder.get(environment)
		                                       .bind("gateway.ledger.executor", LedgerExecutorProperties.class)
		                                       .get();

		assertEquals(8, bound.corePoolSize());
		assertEquals(16, bound.maxPoolSize());
		assertEquals(10_000, bound.queueCapacity());
		assertEquals(30, bound.awaitTerminationSeconds());
		assertTrue(VALIDATOR.validate(bound).isEmpty());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.ledger.executor.max-pool-size", "16"));

		LedgerExecutorProperties bound = Binder.get(environment)
		                                       .bind("gateway.ledger.executor", LedgerExecutorProperties.class)
		                                       .get();

		assertEquals(2, bound.corePoolSize());
		assertEquals(16, bound.maxPoolSize());
		assertEquals(1_000, bound.queueCapacity());
		assertEquals(10, bound.awaitTerminationSeconds());
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(0, 4, 1_000, 10)).isEmpty());
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(65, 4, 1_000, 10)).isEmpty());
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(2, 0, 1_000, 10)).isEmpty());
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(2, 4, 99, 10)).isEmpty());
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(2, 4, 1_000, 0)).isEmpty());
		assertFalse(VALIDATOR.validate(new LedgerExecutorProperties(2, 4, 1_000, 301)).isEmpty());
		assertTrue(VALIDATOR.validate(new LedgerExecutorProperties(1, 1, 100, 1)).isEmpty());
		assertTrue(VALIDATOR.validate(new LedgerExecutorProperties(64, 64, 1_000_000, 300)).isEmpty());
	}

	@Test
	@DisplayName("ledger executor honors explicit ceilings")
	void executorHonorsCeilings() {
		LedgerConfig config = new LedgerConfig();
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				config.ledgerExecutor(new LedgerExecutorProperties(8, 16, 10_000, 30));

		assertEquals(8, executor.getCorePoolSize());
		assertEquals(16, executor.getMaxPoolSize());
		assertEquals(10_000, executor.getQueueCapacity());
		executor.shutdown();
	}
}
