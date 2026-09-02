package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Map;

/**
 * The wire dialect spoken with one upstream provider.
 *
 * <p>The client always speaks OpenAI format. Each adapter owns the details of
 * one native protocol: where to send the request, how to translate the OpenAI body, which headers to attach, and how to
 * normalize the streaming response back to OpenAI shape. {@link OpenAiPassthroughAdapter} needs no translation;
 * {@link AnthropicAdapter}, {@link GeminiAdapter}, {@link DeepSeekAdapter}, and {@link OllamaAdapter} translate in both directions.</p>
 *
 * <p>Adapters are stateless singletons. Normalizers carry per stream state, so
 * {@link #newNormalizer(boolean, String)} hands out a fresh instance per response.</p>
 */
public sealed interface ProtocolAdapter permits OpenAiPassthroughAdapter,
                                                AnthropicAdapter,
                                                OllamaAdapter,
                                                GeminiAdapter,
                                                DeepSeekAdapter {

	/**
	 * Builds the upstream endpoint URL for this protocol.
	 *
	 * @param config the provider to contact
	 * @return the full upstream endpoint for this protocol
	 */
	URI buildUpstreamUrl(ProviderConfig config);

	/**
	 * Translates the client body into the provider's native request body.
	 *
	 * @param rawRequestBody the client body, OpenAI shaped
	 * @param modelOverride  model remapping for this chain step, may be {@code null}
	 * @return the serialized native request body
	 */
	String buildRequestBody(String rawRequestBody, @Nullable String modelOverride);

	/**
	 * Builds the HTTP headers required for this protocol.
	 *
	 * @param config the provider to contact
	 * @return the headers this protocol requires (content type, credentials)
	 */
	Map<String, String> buildRequestHeaders(ProviderConfig config);

	/**
	 * Creates a fresh streaming normalizer for one response.
	 *
	 * @param includeUsageInResponse whether the usage chunk is relayed to the client (the client asked for it)
	 * @param fallbackModel          client requested model, used for cost attribution when the provider never reports
	 *                               its own model id
	 * @return a new normalizer instance
	 */
	SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel);
}