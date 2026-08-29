package io.github.kxng0109.aegisgate.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the defensive copying behavior of {@link VirtualApiKey}: the allow lists are stored as immutable snapshots,
 * so callers cannot mutate key metadata after it is resolved, and null input degrades to empty sets.
 */
@DisplayName("VirtualApiKey")
class VirtualApiKeyTest {

	@Test
	@DisplayName("allow lists are stored as immutable snapshots")
	void allowListsAreStoredAsImmutableSnapshots() {
		Set<String> models = new LinkedHashSet<>();
		models.add("gpt-56-luna");
		Set<String> providers = new LinkedHashSet<>();
		providers.add("openai");

		VirtualApiKey key = key(models, providers);

		models.add("sneaky-model");
		providers.add("sneaky-provider");
		assertEquals(Set.of("gpt-56-luna"), key.allowedModels(), "later mutations to the caller's set must not leak in");
		assertEquals(Set.of("openai"), key.allowedProviders(), "later mutations to the caller's set must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> key.allowedModels().add("x"),
				"the stored allowed models must be unmodifiable"
		);
		assertThrows(
				UnsupportedOperationException.class,
				() -> key.allowedProviders().add("x"),
				"the stored allowed providers must be unmodifiable"
		);
	}

	@Test
	@DisplayName("null allow lists degrade to empty sets")
	void nullAllowListsDegradeToEmptySets() {
		VirtualApiKey key = key(null, null);
		assertTrue(key.allowedModels().isEmpty());
		assertTrue(key.allowedProviders().isEmpty());
	}

	private static VirtualApiKey key(Set<String> allowedModels, Set<String> allowedProviders) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-secret"),
				"gw-",
				"owner",
				"test key",
				0,
				0,
				allowedModels,
				allowedProviders,
				true,
				Instant.parse("2026-01-01T00:00:00Z")
		);
	}
}