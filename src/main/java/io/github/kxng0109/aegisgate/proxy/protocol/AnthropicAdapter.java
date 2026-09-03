package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between the OpenAI chat completions contract and the Anthropic Messages API.
 *
 * <p>Supports text, multi-turn tool loops, tool declarations, tool choices,
 * stop sequences, and token bounds.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class AnthropicAdapter implements ProtocolAdapter {

	public static final String MESSAGES_PATH = "/v1/messages";
	public static final String ANTHROPIC_VERSION = "2023-06-01";
	public static final int DEFAULT_MAX_TOKENS = 4096;
	public static final double MAX_TEMPERATURE = 1.0;

	private final ObjectMapper objectMapper;

	@Override
	public URI buildUpstreamUrl(ProviderConfig config) {
		String baseUrl = stripTrailingSlash(config.baseUrl().toString());
		return URI.create(baseUrl + MESSAGES_PATH);
	}

	@Override
	public String buildRequestBody(String rawRequestBody, @Nullable String modelOverride) {
		OpenAiChatRequest request = parse(rawRequestBody);
		String model = modelOverride != null ? modelOverride : request.model();

		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", model);
		body.put("stream", true);

		List<OpenAiChatRequest.Message> messages = new ArrayList<>();
		StringBuilder system = new StringBuilder();
		if (request.messages() != null) {
			for (OpenAiChatRequest.Message message : request.messages()) {
				String role = message.role() != null ? message.role().toLowerCase() : "";
				if ("system".equals(role) || "developer".equals(role)) {
					String text = messageText(message.content());
					if (text != null && !text.isBlank()) {
						if (!system.isEmpty()) {
							system.append('\n');
						}
						system.append(text);
					}
				} else {
					messages.add(message);
				}
			}
		}
		if (!system.isEmpty()) {
			body.put("system", system.toString());
		}

		ArrayNode anthropicMessages = body.putArray("messages");
		for (OpenAiChatRequest.Message message : messages) {
			String role = message.role() != null ? message.role().toLowerCase() : "";
			if ("assistant".equals(role)) {
				ObjectNode out = anthropicMessages.addObject();
				out.put("role", "assistant");
				ArrayNode contentArray = out.putArray("content");
				contentArray.addAll(contentBlocks(message.content()));
				if (message.toolCalls() != null && message.toolCalls().isArray()) {
					for (JsonNode toolCall : message.toolCalls()) {
						String toolId = toolCall.path("id").asString("");
						String funcName = toolCall.path("function").path("name").asString("");
						String funcArgs = toolCall.path("function").path("arguments").asString("{}");
						if (!funcName.isBlank()) {
							ObjectNode toolUseBlock = contentArray.addObject();
							toolUseBlock.put("type", "tool_use");
							toolUseBlock.put("id", toolId);
							toolUseBlock.put("name", funcName);
							try {
								toolUseBlock.set("input", objectMapper.readTree(funcArgs));
							} catch (Exception e) {
								toolUseBlock.putObject("input");
							}
						}
					}
				}
			} else if ("tool".equals(role)) {
				ObjectNode out = anthropicMessages.addObject();
				out.put("role", "user");
				ArrayNode contentArray = out.putArray("content");
				ObjectNode toolResult = contentArray.addObject();
				toolResult.put("type", "tool_result");
				toolResult.put("tool_use_id", message.toolCallId() != null ? message.toolCallId() : "");
				toolResult.put("content", messageText(message.content()) != null ? messageText(message.content()) : "");
			} else if ("user".equals(role)) {
				ObjectNode out = anthropicMessages.addObject();
				out.put("role", "user");
				out.putArray("content").addAll(contentBlocks(message.content()));
			} else {
				log.debug("Dropping a message with an unsupported role for Anthropic: {}", role);
			}
		}

		body.put("max_tokens", maxTokens(request));

		Double temperature = request.temperature();
		if (temperature != null) {
			body.put("temperature", Math.clamp(temperature, 0.0, MAX_TEMPERATURE));
		}
		Double topP = request.topP();
		if (topP != null) {
			body.put("top_p", topP);
		}
		ArrayNode stopSequences = stopSequences(request.stop());
		if (!stopSequences.isEmpty()) {
			body.set("stop_sequences", stopSequences);
		}

		if (request.tools() != null && request.tools().isArray() && !request.tools().isEmpty()) {
			ArrayNode anthropicTools = UniversalToolNormalizer.toAnthropicTools(request.tools(), objectMapper);
			if (anthropicTools != null) {
				body.set("tools", anthropicTools);
			}
		}

		if (request.toolChoice() != null && !request.toolChoice().isNull()) {
			ObjectNode toolChoiceObj = UniversalToolNormalizer.toAnthropicToolChoice(
					request.toolChoice(), request.parallelToolCalls(), objectMapper
			);
			if (toolChoiceObj != null) {
				body.set("tool_choice", toolChoiceObj);
			}
		}

		return objectMapper.writeValueAsString(body);
	}

	@Override
	public Map<String, String> buildRequestHeaders(ProviderConfig config) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put("x-api-key", config.apiKey() == null ? "" : config.apiKey().value());
		headers.put("anthropic-version", ANTHROPIC_VERSION);
		return headers;
	}

	@Override
	public SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel) {
		return new AnthropicSseNormalizer(objectMapper, fallbackModel, includeUsageInResponse);
	}

	private OpenAiChatRequest parse(String rawRequestBody) {
		return objectMapper.readValue(rawRequestBody, OpenAiChatRequest.class);
	}

	private int maxTokens(OpenAiChatRequest request) {
		Integer bound = request.effectiveMaxTokens();
		return bound != null && bound > 0 ? bound : DEFAULT_MAX_TOKENS;
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

	private ArrayNode contentBlocks(@Nullable JsonNode content) {
		ArrayNode blocks = objectMapper.createArrayNode();
		if (content == null || content.isNull()) {
			return blocks;
		}
		if (content.isString()) {
			blocks.addObject().put("type", "text").put("text", content.asString());
			return blocks;
		}
		if (content.isArray()) {
			for (JsonNode part : content) {
				String type = part.path("type").asString("");
				if ("text".equals(type)) {
					blocks.addObject().put("type", "text").put("text", part.path("text").asString(""));
				} else {
					log.debug("Dropping a content part Anthropic cannot translate: {}", type);
				}
			}
		}
		return blocks;
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
