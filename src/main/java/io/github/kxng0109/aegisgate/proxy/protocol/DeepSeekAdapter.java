package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protocol adapter for DeepSeek API (DeepSeek V3, R1, V4 family).
 *
 * <p>Supports hybrid reasoning mode ({@code thinking: {type: "enabled"}}, {@code reasoning_effort}),
 * streaming {@code reasoning_content}, OpenAI tool calling, and prompt caching.</p>
 */
@Component
@RequiredArgsConstructor
public final class DeepSeekAdapter implements ProtocolAdapter {

	public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

	private final ObjectMapper objectMapper;

	@Override
	public URI buildUpstreamUrl(ProviderConfig config) {
		String baseUrl = stripTrailingSlash(config.baseUrl().toString());
		if (baseUrl.endsWith("/chat/completions")) {
			return URI.create(baseUrl);
		}
		return URI.create(baseUrl + CHAT_COMPLETIONS_PATH);
	}

	@Override
	public String buildRequestBody(String rawRequestBody, @Nullable String modelOverride) {
		OpenAiChatRequest request = parse(rawRequestBody);
		String model = modelOverride != null ? modelOverride : request.model();

		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", model);
		body.put("stream", true);

		ArrayNode outMessages = body.putArray("messages");
		if (request.messages() != null) {
			for (OpenAiChatRequest.Message msg : request.messages()) {
				ObjectNode m = outMessages.addObject();
				if (msg.role() != null) {
					m.put("role", msg.role());
				}
				if (msg.content() != null) {
					m.set("content", msg.content().deepCopy());
				}
				if (msg.name() != null) {
					m.put("name", msg.name());
				}
				if (msg.toolCallId() != null) {
					m.put("tool_call_id", msg.toolCallId());
				}
				if (msg.toolCalls() != null && msg.toolCalls().isArray()) {
					m.set("tool_calls", msg.toolCalls().deepCopy());
				}
				if (msg.reasoningContent() != null) {
					m.put("reasoning_content", msg.reasoningContent());
				}
			}
		}

		if (request.temperature() != null) {
			body.put("temperature", request.temperature());
		}
		if (request.topP() != null) {
			body.put("top_p", request.topP());
		}
		Integer maxTokens = request.effectiveMaxTokens();
		if (maxTokens != null && maxTokens > 0) {
			body.put("max_tokens", maxTokens);
		}
		if (request.stop() != null && !request.stop().isNull()) {
			body.set("stop", request.stop().deepCopy());
		}
		if (request.tools() != null && request.tools().isArray() && !request.tools().isEmpty()) {
			body.set("tools", request.tools().deepCopy());
		}
		if (request.toolChoice() != null && !request.toolChoice().isNull()) {
			body.set("tool_choice", request.toolChoice().deepCopy());
		}
		if (request.responseFormat() != null && request.responseFormat().isObject()) {
			body.set("response_format", request.responseFormat().deepCopy());
		}
		if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
			body.put("reasoning_effort", request.reasoningEffort());
		}
		if (request.thinking() != null && request.thinking().isObject()) {
			body.set("thinking", request.thinking().deepCopy());
		} else if (model.contains("reasoner") || model.contains("r1")) {
			body.putObject("thinking").put("type", "enabled");
		}

		if (request.streamOptions() != null && request.streamOptions().isObject()) {
			body.set("stream_options", request.streamOptions().deepCopy());
		}

		return objectMapper.writeValueAsString(body);
	}

	@Override
	public Map<String, String> buildRequestHeaders(ProviderConfig config) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		String key = config.apiKey() == null ? "" : config.apiKey().value();
		headers.put("Authorization", "Bearer " + key);
		return headers;
	}

	@Override
	public SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel) {
		return new DeepSeekSseNormalizer(objectMapper, fallbackModel, includeUsageInResponse);
	}

	private OpenAiChatRequest parse(String rawRequestBody) {
		return objectMapper.readValue(rawRequestBody, OpenAiChatRequest.class);
	}

	private static String stripTrailingSlash(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}
}
