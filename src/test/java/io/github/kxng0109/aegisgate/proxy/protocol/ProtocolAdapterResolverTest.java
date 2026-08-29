package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProtocolAdapterResolver}.
 */
@DisplayName("ProtocolAdapterResolver")
class ProtocolAdapterResolverTest {

	private final ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
			new OpenAiPassthroughAdapter(new ObjectMapper()),
			new AnthropicAdapter(new ObjectMapper()),
			new OllamaAdapter(new ObjectMapper())
	);

	@Test
	@DisplayName("maps every provider type to its adapter")
	void resolvesEachType() {
		assertInstanceOf(OpenAiPassthroughAdapter.class, resolver.resolve(ProviderType.OPENAI));
		assertInstanceOf(AnthropicAdapter.class, resolver.resolve(ProviderType.ANTHROPIC));
		assertInstanceOf(OllamaAdapter.class, resolver.resolve(ProviderType.OLLAMA));
	}
}