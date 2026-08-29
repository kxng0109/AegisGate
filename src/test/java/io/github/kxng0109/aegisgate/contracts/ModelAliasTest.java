package io.github.kxng0109.aegisgate.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the defensive copying behavior of {@link ModelAlias}: the chain is stored as an immutable snapshot, so
 * callers cannot mutate the alias after it is bound, and null input degrades to an empty list.
 */
@DisplayName("ModelAlias")
class ModelAliasTest {

	@Test
	@DisplayName("chain is stored as an immutable snapshot")
	void chainIsStoredAsImmutableSnapshot() {
		List<ProviderRef> mutable = new ArrayList<>();
		mutable.add(new ProviderRef("openai", null));
		ModelAlias alias = new ModelAlias(mutable, FailoverStrategy.SEQUENTIAL);

		mutable.add(new ProviderRef("ollama", null));
		assertEquals(List.of(new ProviderRef("openai", null)), alias.chain(), "later mutations to the caller's list must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> alias.chain().add(new ProviderRef("x", null)),
				"the stored chain must be unmodifiable"
		);
	}

	@Test
	@DisplayName("null chain degrades to an empty list")
	void nullChainDegradesToEmptyList() {
		ModelAlias alias = new ModelAlias(null, FailoverStrategy.SEQUENTIAL);
		assertTrue(alias.chain().isEmpty());
	}
}