package io.github.kxng0109.aegisgate.proxy.embeddings;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmbeddingProperties")
class EmbeddingPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		EmbeddingProperties props = EmbeddingProperties.DEFAULTS;

		assertEquals(2_048, props.maxBatchItems());
		assertEquals(4, props.maxConcurrentSubRequests());
		assertTrue(VALIDATOR.validate(props).isEmpty());
	}

	@Test
	@DisplayName("properties bind from the gateway.embeddings prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.embeddings.max-batch-items", "512")
				                                          .withProperty(
						                                          "gateway.embeddings.max-concurrent-sub-requests",
						                                          "8"
				                                          ));

		EmbeddingProperties bound = Binder.get(environment)
		                                  .bind("gateway.embeddings", EmbeddingProperties.class)
		                                  .get();

		assertEquals(512, bound.maxBatchItems());
		assertEquals(8, bound.maxConcurrentSubRequests());
		assertTrue(VALIDATOR.validate(bound).isEmpty());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty(
						                                          "gateway.embeddings.max-concurrent-sub-requests",
						                                          "8"
				                                          ));

		EmbeddingProperties bound = Binder.get(environment)
		                                  .bind("gateway.embeddings", EmbeddingProperties.class)
		                                  .get();

		assertEquals(2_048, bound.maxBatchItems());
		assertEquals(8, bound.maxConcurrentSubRequests());
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertFalse(VALIDATOR.validate(new EmbeddingProperties(0, 4)).isEmpty());
		assertFalse(VALIDATOR.validate(new EmbeddingProperties(100_001, 4)).isEmpty());
		assertFalse(VALIDATOR.validate(new EmbeddingProperties(2_048, 0)).isEmpty());
		assertFalse(VALIDATOR.validate(new EmbeddingProperties(2_048, 65)).isEmpty());
		assertTrue(VALIDATOR.validate(new EmbeddingProperties(1, 1)).isEmpty());
		assertTrue(VALIDATOR.validate(new EmbeddingProperties(100_000, 64)).isEmpty());
	}
}
