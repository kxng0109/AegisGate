package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter for providers that already speak the OpenAI chat completions
 * protocol (OpenAI itself, OpenRouter, Groq, DeepSeek, Mistral, Together,
 * vLLM, and most local servers).
 *
 * <p>The body is forwarded as is, with two surgical edits: the model override
 * when a chain step requests one, and {@code stream_options.include_usage}
 * forced to {@code true} so the upstream always reports token usage. Without
 * usage the gateway could not bill, and clients that did not ask for usage
 * never see the extra chunk because the normalizer drops it.</p>
 */
@Component
@RequiredArgsConstructor
public final class OpenAiPassthroughAdapter implements ProtocolAdapter {

	/**
	 * The OpenAI compatible chat completions path.
	 */
	public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

	private final ObjectMapper objectMapper;

	@Override
	public URI buildUpstreamUrl(ProviderConfig config) {
		String baseUrl = stripTrailingSlash(config.baseUrl().toString());
		return URI.create(baseUrl + CHAT_COMPLETIONS_PATH);
	}

	@Override
	public String buildRequestBody(String rawRequestBody, @Nullable String modelOverride) {
		JsonNode root;
		try {
			root = objectMapper.readTree(rawRequestBody);
		} catch (JacksonException ex) {
			// An unparseable body cannot be rewritten. Forward it untouched:
			// the upstream will reject it as its own client error, which the
			// orchestrator surfaces without failing over the whole chain.
			return rawRequestBody;
		}
		ObjectNode rewritten = (ObjectNode) root.deepCopy();
		if (modelOverride != null) {
			rewritten.put("model", modelOverride);
		}
		ObjectNode streamOptions = streamOptions(rewritten);
		streamOptions.put("include_usage", true);
		return objectMapper.writeValueAsString(rewritten);
	}

	@Override
	public Map<String, String> buildRequestHeaders(ProviderConfig config) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		String apiKey = config.apiKey() == null ? "" : config.apiKey().value();
		if (!apiKey.isBlank()) {
			headers.put("Authorization", "Bearer " + apiKey);
		}
		return headers;
	}

	@Override
	public SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel) {
		return new OpenAiSseNormalizer(objectMapper, fallbackModel, includeUsageInResponse);
	}

	private ObjectNode streamOptions(ObjectNode request) {
		JsonNode existing = request.get("stream_options");
		if (existing != null && existing.isObject()) {
			return (ObjectNode) existing;
		}
		ObjectNode options = objectMapper.createObjectNode();
		request.set("stream_options", options);
		return options;
	}

	private static String stripTrailingSlash(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}
}