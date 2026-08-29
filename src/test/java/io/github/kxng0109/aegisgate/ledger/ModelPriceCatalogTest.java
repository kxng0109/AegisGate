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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModelPriceCatalog}: exact, composite, and prefix matching, plus the empty result for unknown
 * models.
 */
@DisplayName("ModelPriceCatalog")
class ModelPriceCatalogTest {

	private final ModelPricingRepository repository = mock(ModelPricingRepository.class);
	private final ModelPriceCatalog catalog = new ModelPriceCatalog(repository);

	@Test
	@DisplayName("matches an exact model id, preferring the same provider")
	void exactMatch() {
		when(repository.findAll()).thenReturn(List.of(
				entity("gpt-5.6-sol", "openai", "0.000004", "0.00002"),
				entity("gpt-5.6-sol", "other", "0.000009", "0.00009")
		));

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol");

		assertTrue(found.isPresent());
		assertEquals("openai", found.get().provider());
		assertEquals(0, new BigDecimal("0.000004").compareTo(found.get().inputCostPerToken()));
	}

	@Test
	@DisplayName("an exact id under another provider still matches")
	void exactIdOtherProvider() {
		when(repository.findAll()).thenReturn(List.of(
				entity("gpt-5.6-sol", "other", "0.000009", "0.00009")
		));

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol");

		assertTrue(found.isPresent());
		assertEquals("other", found.get().provider());
	}

	@Test
	@DisplayName("matches the provider slash model composite key")
	void compositeMatch() {
		when(repository.findAll()).thenReturn(List.of(
				entity("ollama/llama3.2", "ollama", "0.0", "0.0")
		));

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.OLLAMA, "llama3.2");

		assertTrue(found.isPresent());
		assertEquals("ollama", found.get().provider());
	}

	@Test
	@DisplayName("matches the longest registered model id that prefixes the reported one")
	void prefixMatch() {
		when(repository.findAll()).thenReturn(List.of(
				entity("claude-sonnet-5", "anthropic", "0.000002", "0.00001"),
				entity("claude-haiku-4-5", "anthropic", "0.000001", "0.000005")
		));

		Optional<ModelPricingEntry> found = catalog.lookup(ProviderType.ANTHROPIC, "claude-sonnet-5-20251001");

		assertTrue(found.isPresent());
		assertEquals("claude-sonnet-5", found.get().modelId());
	}

	@Test
	@DisplayName("returns empty for a blank model")
	void blankModel() {
		when(repository.findAll()).thenReturn(List.of(entity("gpt-5.6-sol", "openai", "0.000004", "0.00002")));

		assertTrue(catalog.lookup(ProviderType.OPENAI, null).isEmpty());
		assertTrue(catalog.lookup(ProviderType.OPENAI, "  ").isEmpty());
	}

	@Test
	@DisplayName("returns empty for an unknown model")
	void unknownModel() {
		when(repository.findAll()).thenReturn(List.of(entity("gpt-5.6-sol", "openai", "0.000004", "0.00002")));

		assertTrue(catalog.lookup(ProviderType.OPENAI, "not-a-model").isEmpty());
	}

	@Test
	@DisplayName("the catalog is loaded once and cached for repeated lookups")
	void cachesSnapshot() {
		when(repository.findAll()).thenReturn(List.of(entity("gpt-5.6-sol", "openai", "0.000004", "0.00002")));
		catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol");
		catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol");
		catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol");
		verify(repository, times(1)).findAll();
	}

	private static ModelPricingEntity entity(String modelId, String provider, String input, String output) {
		return new ModelPricingEntity(
				modelId, provider, "chat",
				new BigDecimal(input), new BigDecimal(output),
				null, null, null, null,
				"https://example.test/prices.json", Instant.now()
		);
	}
}