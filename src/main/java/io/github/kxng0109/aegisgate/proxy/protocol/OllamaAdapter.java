package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates between the OpenAI chat completions contract and the Ollama chat API.
 *
 * <p>Ollama's message shape is already OpenAI compatible, so the body maps
 * almost directly. Sampling parameters that have an Ollama equivalent land in the {@code options} map: temperature,
 * top_p, stop sequences, and the completion bound as {@code num_predict}. Local Ollama instances need no credentials,
 * so no auth header is sent.</p>
 */
@Component
@RequiredArgsConstructor
public final class OllamaAdapter implements ProtocolAdapter {

	/**
	 * The Ollama chat API path.
	 */
	public static final String CHAT_PATH = "/api/chat";

	private final ObjectMapper objectMapper;

	@Override
	public URI buildUpstreamUrl(ProviderConfig config) {
		String baseUrl = stripTrailingSlash(config.baseUrl().toString());
		return URI.create(baseUrl + CHAT_PATH);
	}

	@Override
	public String buildRequestBody(String rawRequestBody, @Nullable String modelOverride) {
		OpenAiChatRequest request = parse(rawRequestBody);
		String model = modelOverride != null ? modelOverride : request.model();

		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", model);
		body.put("stream", true);

		ArrayNode messages = body.putArray("messages");
		if (request.messages() != null) {
			for (OpenAiChatRequest.Message message : request.messages()) {
				String role = message.role();
				String text = messageText(message.content());
				if (role == null || role.isBlank() || text == null) {
					continue;
				}
				ObjectNode out = messages.addObject();
				out.put("role", role);
				out.put("content", text);
			}
		}

		ObjectNode options = body.putObject("options");
		Double temperature = request.temperature();
		if (temperature != null) {
			options.put("temperature", temperature);
		}
		Double topP = request.topP();
		if (topP != null) {
			options.put("top_p", topP);
		}
		ArrayNode stop = stopSequences(request.stop());
		if (!stop.isEmpty()) {
			options.set("stop", stop);
		}
		Integer bound = request.effectiveMaxTokens();
		if (bound != null && bound > 0) {
			options.put("num_predict", bound);
		}

		return objectMapper.writeValueAsString(body);
	}

	@Override
	public Map<String, String> buildRequestHeaders(ProviderConfig config) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		return headers;
	}

	@Override
	public SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel) {
		return new OllamaSseNormalizer(objectMapper, fallbackModel, includeUsageInResponse);
	}

	private OpenAiChatRequest parse(String rawRequestBody) {
		return objectMapper.readValue(rawRequestBody, OpenAiChatRequest.class);
	}

	private ArrayNode stopSequences(@Nullable JsonNode stop) {
		ArrayNode out = objectMapper.createArrayNode();
		if (stop == null || stop.isNull()) {
			return out;
		}
		if (stop.isString()) {
			out.add(stop.asString());
		} else if (stop.isArray()) {
			for (JsonNode entry : stop) {
				if (entry.isString()) {
					out.add(entry.asString());
				}
			}
		}
		return out;
	}

	private @Nullable String messageText(@Nullable JsonNode content) {
		if (content == null || content.isNull()) {
			return null;
		}
		if (content.isString()) {
			return content.asString();
		}
		StringBuilder text = new StringBuilder();
		if (content.isArray()) {
			for (JsonNode part : content) {
				if ("text".equals(part.path("type").asString(""))) {
					if (!text.isEmpty()) {
						text.append('\n');
					}
					text.append(part.path("text").asString(""));
				}
			}
		}
		return text.isEmpty() ? null : text.toString();
	}

	private static String stripTrailingSlash(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}
}