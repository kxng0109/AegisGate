package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the V5 local Ollama pricing rows end to end through the real {@link ModelPriceCatalog} and
 * {@link CostCalculator}: the client alias, the provider wire id, and the embedding id each resolve to zero cost
 * without warnings, while unknown and mis-cased ids still fall through to the zero cost fallback.
 */
@DisplayName("OllamaLocalPricing")
class OllamaLocalPricingTest {

	private final ModelPricingRepository repository = mock(ModelPricingRepository.class);
	private final ModelPriceCatalog catalog = new ModelPriceCatalog(repository);
	private final CostCalculator calculator = new CostCalculator(catalog);

	@Test
	@DisplayName("P1: the client alias resolves to zero cost")
	void clientAliasResolvesToZeroCost() {
		givenV5Rows();

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OLLAMA, "local-llama");

		assertTrue(found.isPresent(), "V5 must seed the non-streaming chat alias verbatim");
		assertEquals("ollama", found.get().provider());
		assertEquals(0, calculator.calculate(ProviderType.OLLAMA, "local-llama", 1000, 500));
	}

	@Test
	@DisplayName("P2: the provider wire id resolves to zero cost")
	void providerWireIdResolvesToZeroCost() {
		givenV5Rows();

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OLLAMA, "qwen2.5:0.5b");

		assertTrue(found.isPresent(), "V5 must seed the streaming chat wire id verbatim");
		assertEquals("ollama", found.get().provider());
		assertEquals(0, calculator.calculate(ProviderType.OLLAMA, "qwen2.5:0.5b", 1000, 500));
	}

	@Test
	@DisplayName("P3: the embedding id resolves to zero cost")
	void embeddingIdResolvesToZeroCost() {
		givenV5Rows();

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OLLAMA, "nomic-embed-text:latest");

		assertTrue(found.isPresent(), "V5 must seed the embedding id verbatim");
		assertEquals(0, calculator.calculate(ProviderType.OLLAMA, "nomic-embed-text:latest", 1000, 0));
	}

	@Test
	@DisplayName("P4: an unseeded model still costs zero and never fails")
	void unseededModelStillCostsZero() {
		givenV5Rows();

		assertTrue(catalog.lookup(ProviderType.OLLAMA, "qwen2.5:7b").isEmpty());
		assertEquals(0, calculator.calculate(ProviderType.OLLAMA, "qwen2.5:7b", 100, 100));
	}

	@Test
	@DisplayName("P6: lookups stay case-sensitive so near misses never misprice")
	void lookupsStayCaseSensitive() {
		givenV5Rows();

		assertTrue(catalog.lookup(ProviderType.OLLAMA, "Local-Llama").isEmpty());
		assertTrue(catalog.lookup(ProviderType.OLLAMA, "QWEN2.5:0.5B").isEmpty());
	}

	private void givenV5Rows() {
		when(repository.findAll()).thenReturn(List.of(
				entity("local-llama", "ollama", "chat"),
				entity("qwen2.5:0.5b", "ollama", "chat"),
				entity("nomic-embed-text:latest", "ollama", "embedding")
		));
	}

	private static ModelPricingEntity entity(String modelId, String provider, String mode) {
		return new ModelPricingEntity(
				modelId, provider, mode,
				BigDecimal.ZERO, BigDecimal.ZERO,
				null, null, null, null,
				"local", Instant.now()
		);
	}
}
