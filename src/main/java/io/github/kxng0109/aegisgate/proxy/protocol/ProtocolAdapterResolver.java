package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link ProtocolAdapter} for a provider's declared dialect.
 */
@Component
@RequiredArgsConstructor
public class ProtocolAdapterResolver {

	private final OpenAiPassthroughAdapter openAiPassthroughAdapter;
	private final AnthropicAdapter anthropicAdapter;
	private final OllamaAdapter ollamaAdapter;

	/**
	 * @param type the provider dialect
	 * @return the adapter that speaks that dialect
	 */
	public ProtocolAdapter resolve(ProviderType type) {
		return switch (type) {
			case OPENAI -> openAiPassthroughAdapter;
			case ANTHROPIC -> anthropicAdapter;
			case OLLAMA -> ollamaAdapter;
		};
	}
}