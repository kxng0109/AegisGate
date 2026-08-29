package io.github.kxng0109.aegisgate.contracts;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the defensive copying behavior of {@link GatewayProperties}: setters store unmodifiable snapshots, so
 * callers cannot mutate configuration after it is bound, and null input degrades to empty collections.
 */
@DisplayName("GatewayProperties")
class GatewayPropertiesTest {

	@Test
	@DisplayName("providers are stored as an unmodifiable snapshot")
	void providersAreStoredAsUnmodifiableSnapshot() {
		Map<String, ProviderConfig> mutable = new LinkedHashMap<>();
		mutable.put("openai", provider());
		GatewayProperties properties = new GatewayProperties();
		properties.setProviders(mutable);

		mutable.put("late", provider());
		assertEquals(1, properties.getProviders().size(), "later mutations to the caller's map must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> properties.getProviders().put("x", provider()),
				"the stored providers must be unmodifiable"
		);
	}

	@Test
	@DisplayName("aliases are stored as an unmodifiable snapshot")
	void aliasesAreStoredAsUnmodifiableSnapshot() {
		Map<String, ModelAlias> mutable = new LinkedHashMap<>();
		mutable.put("fast", alias());
		GatewayProperties properties = new GatewayProperties();
		properties.setAliases(mutable);

		mutable.put("late", alias());
		assertEquals(1, properties.getAliases().size(), "later mutations to the caller's map must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> properties.getAliases().put("x", alias()),
				"the stored aliases must be unmodifiable"
		);
	}

	@Test
	@DisplayName("bootstrap keys are stored as an unmodifiable snapshot")
	void bootstrapKeysAreStoredAsUnmodifiableSnapshot() {
		List<BootstrapKey> mutable = new ArrayList<>();
		mutable.add(bootstrapKey());
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(mutable);

		mutable.add(bootstrapKey());
		assertEquals(1, properties.getBootstrapKeys().size(), "later mutations to the caller's list must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> properties.getBootstrapKeys().add(bootstrapKey()),
				"the stored bootstrap keys must be unmodifiable"
		);
	}

	@Test
	@DisplayName("null providers bind to an empty map")
	void nullProvidersBindToEmptyMap() {
		GatewayProperties properties = new GatewayProperties();
		properties.setProviders(null);
		assertTrue(properties.getProviders().isEmpty());
	}

	@Test
	@DisplayName("null aliases bind to an empty map")
	void nullAliasesBindToEmptyMap() {
		GatewayProperties properties = new GatewayProperties();
		properties.setAliases(null);
		assertTrue(properties.getAliases().isEmpty());
	}

	@Test
	@DisplayName("null bootstrap keys bind to an empty list")
	void nullBootstrapKeysBindToEmptyList() {
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(null);
		assertTrue(properties.getBootstrapKeys().isEmpty());
	}

	private static ProviderConfig provider() {
		return new ProviderConfig(
				"openai",
				ProviderType.OPENAI,
				URI.create("https://example.invalid/v1"),
				new SensitiveString("sk-test"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(30)
		);
	}

	private static ModelAlias alias() {
		return new ModelAlias(List.of(), FailoverStrategy.SEQUENTIAL);
	}

	private static BootstrapKey bootstrapKey() {
		return new BootstrapKey("owner", "name", "gw-test", 0, 0, null, null);
	}
}