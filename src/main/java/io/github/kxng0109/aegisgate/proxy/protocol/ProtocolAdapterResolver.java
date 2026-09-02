package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link ProtocolAdapter} for a provider's declared dialect.
 */
@Component
public class ProtocolAdapterResolver {

	private final OpenAiPassthroughAdapter openAiPassthroughAdapter;
	private final AnthropicAdapter anthropicAdapter;
	private final GeminiAdapter geminiAdapter;
	private final DeepSeekAdapter deepSeekAdapter;
	private final OllamaAdapter ollamaAdapter;

	/**
	 * Canonical constructor injecting all registered protocol adapters.
	 *
	 * @param openAiPassthroughAdapter adapter for OpenAI-compatible providers
	 * @param anthropicAdapter         adapter for Anthropic Claude Messages API
	 * @param geminiAdapter            adapter for Google Gemini and Vertex AI REST APIs
	 * @param deepSeekAdapter          adapter for DeepSeek Reasoner and Chat APIs
	 * @param ollamaAdapter            adapter for Ollama local inference API
	 */
	public ProtocolAdapterResolver(OpenAiPassthroughAdapter openAiPassthroughAdapter,
	                               AnthropicAdapter anthropicAdapter,
	                               GeminiAdapter geminiAdapter,
	                               DeepSeekAdapter deepSeekAdapter,
	                               OllamaAdapter ollamaAdapter) {
		this.openAiPassthroughAdapter = openAiPassthroughAdapter;
		this.anthropicAdapter = anthropicAdapter;
		this.geminiAdapter = geminiAdapter;
		this.deepSeekAdapter = deepSeekAdapter;
		this.ollamaAdapter = ollamaAdapter;
	}

	/**
	 * Resolves the protocol adapter for the specified provider type.
	 *
	 * @param type the provider dialect
	 * @return the adapter that speaks that dialect
	 */
	public ProtocolAdapter resolve(ProviderType type) {
		return switch (type) {
			case OPENAI -> openAiPassthroughAdapter;
			case ANTHROPIC -> anthropicAdapter;
			case GEMINI, VERTEX_AI -> geminiAdapter;
			case DEEPSEEK -> deepSeekAdapter;
			case OLLAMA -> ollamaAdapter;
		};
	}
}