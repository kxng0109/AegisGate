package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for {@link ProtocolAdapterResolver}.
 */
@DisplayName("ProtocolAdapterResolver")
@SuppressWarnings("DataFlowIssue")
class ProtocolAdapterResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
			new OpenAiPassthroughAdapter(objectMapper),
			new AnthropicAdapter(objectMapper),
			new GeminiAdapter(objectMapper),
			new DeepSeekAdapter(objectMapper),
			new OllamaAdapter(objectMapper)
	);

	@Test
	@DisplayName("maps every provider type to its adapter")
	void resolvesEachType() {
		assertInstanceOf(OpenAiPassthroughAdapter.class, resolver.resolve(ProviderType.OPENAI));
		assertInstanceOf(AnthropicAdapter.class, resolver.resolve(ProviderType.ANTHROPIC));
		assertInstanceOf(GeminiAdapter.class, resolver.resolve(ProviderType.GEMINI));
		assertInstanceOf(GeminiAdapter.class, resolver.resolve(ProviderType.VERTEX_AI));
		assertInstanceOf(DeepSeekAdapter.class, resolver.resolve(ProviderType.DEEPSEEK));
		assertInstanceOf(OllamaAdapter.class, resolver.resolve(ProviderType.OLLAMA));
	}
}
