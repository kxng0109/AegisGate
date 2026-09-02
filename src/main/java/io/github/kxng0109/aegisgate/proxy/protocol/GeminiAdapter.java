package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
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
 * Translates between the OpenAI chat completions contract and the Google Gemini / Vertex AI REST protocol.
 *
 * <p>Supports both Google AI Studio Developer API ({@code generativelanguage.googleapis.com})
 * and Google Cloud Vertex AI ({@code aiplatform.googleapis.com}).</p>
 */
@Component
@RequiredArgsConstructor
public final class GeminiAdapter implements ProtocolAdapter {

	/**
	 * Default models path for Gemini Developer API.
	 */
	public static final String DEVELOPER_API_STREAM_PATH = "/v1beta/models/%s:streamGenerateContent?alt=sse";

	/**
	 * Default fallback model for Gemini when none specified.
	 */
	public static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";

	private final ObjectMapper objectMapper;

	@Override
	public URI buildUpstreamUrl(ProviderConfig config) {
		String baseUrl = stripTrailingSlash(config.baseUrl().toString());
		if (baseUrl.contains(":streamGenerateContent") || baseUrl.contains(":generateContent")) {
			return URI.create(baseUrl);
		}
		if (config.type() == ProviderType.VERTEX_AI) {
			return URI.create(baseUrl + ":streamGenerateContent?alt=sse");
		}
		if (baseUrl.endsWith("/models")) {
			return URI.create(baseUrl + "/" + DEFAULT_GEMINI_MODEL + ":streamGenerateContent?alt=sse");
		}
		return URI.create(baseUrl + String.format(DEVELOPER_API_STREAM_PATH, DEFAULT_GEMINI_MODEL));
	}

	@Override
	public String buildRequestBody(String rawRequestBody, @Nullable String modelOverride) {
		OpenAiChatRequest request = parse(rawRequestBody);
		ObjectNode body = objectMapper.createObjectNode();

		List<OpenAiChatRequest.Message> messages = new ArrayList<>();
		StringBuilder systemInstruction = new StringBuilder();

		if (request.messages() != null) {
			for (OpenAiChatRequest.Message message : request.messages()) {
				String role = message.role() != null ? message.role().toLowerCase() : "";
				if ("system".equals(role) || "developer".equals(role)) {
					String text = messageText(message.content());
					if (text != null && !text.isBlank()) {
						if (!systemInstruction.isEmpty()) {
							systemInstruction.append('\n');
						}
						systemInstruction.append(text);
					}
				} else {
					messages.add(message);
				}
			}
		}

		if (!systemInstruction.isEmpty()) {
			ObjectNode systemNode = body.putObject("systemInstruction");
			systemNode.put("role", "system");
			systemNode.putArray("parts").addObject().put("text", systemInstruction.toString());
		}

		ArrayNode contents = body.putArray("contents");
		for (OpenAiChatRequest.Message message : messages) {
			String role = message.role() != null ? message.role().toLowerCase() : "user";
			ObjectNode contentTurn = contents.addObject();

			if ("assistant".equals(role)) {
				contentTurn.put("role", "model");
				ArrayNode parts = contentTurn.putArray("parts");
				String text = messageText(message.content());
				if (text != null && !text.isBlank()) {
					parts.addObject().put("text", text);
				}
				if (message.toolCalls() != null && message.toolCalls().isArray()) {
					for (JsonNode toolCall : message.toolCalls()) {
						String funcName = toolCall.path("function").path("name").asString("");
						String funcArgs = toolCall.path("function").path("arguments").asString("{}");
						if (!funcName.isBlank()) {
							ObjectNode funcCallObj = parts.addObject().putObject("functionCall");
							funcCallObj.put("name", funcName);
							try {
								funcCallObj.set("args", objectMapper.readTree(funcArgs));
							} catch (Exception e) {
								funcCallObj.putObject("args");
							}
						}
					}
				}
			} else if ("tool".equals(role)) {
				contentTurn.put("role", "user");
				ArrayNode parts = contentTurn.putArray("parts");
				String funcName = message.name() != null ? message.name() : "tool_response";
				String contentStr = messageText(message.content());
				ObjectNode funcResponse = parts.addObject().putObject("functionResponse");
				funcResponse.put("name", funcName);
				funcResponse.set(
						"response",
						UniversalToolNormalizer.normalizeToolResultForGemini(contentStr, objectMapper)
				);
			} else {
				contentTurn.put("role", "user");
				ArrayNode parts = contentTurn.putArray("parts");
				appendUserContentParts(message.content(), parts);
			}
		}

		ObjectNode generationConfig = body.putObject("generationConfig");
		if (request.temperature() != null) {
			generationConfig.put("temperature", request.temperature());
		}
		if (request.topP() != null) {
			generationConfig.put("topP", request.topP());
		}
		Integer maxTokens = request.effectiveMaxTokens();
		if (maxTokens != null && maxTokens > 0) {
			generationConfig.put("maxOutputTokens", maxTokens);
		}
		ArrayNode stopSequences = stopSequences(request.stop());
		if (!stopSequences.isEmpty()) {
			generationConfig.set("stopSequences", stopSequences);
		}

		if (request.responseFormat() != null && request.responseFormat().isObject()) {
			String formatType = request.responseFormat().path("type").asString("");
			if ("json_object".equals(formatType) || "json_schema".equals(formatType)) {
				generationConfig.put("responseMimeType", "application/json");
				if ("json_schema".equals(formatType) && request.responseFormat().has("json_schema")) {
					JsonNode schemaNode = request.responseFormat().path("json_schema").path("schema");
					if (schemaNode.isObject()) {
						generationConfig.set(
								"responseSchema",
								UniversalToolNormalizer.toGeminiParameters(schemaNode, objectMapper)
						);
					}
				}
			}
		}

		if (request.reasoningEffort() != null || request.thinking() != null) {
			generationConfig.putObject("thinkingConfig").put("includeThoughts", true);
		}

		if (request.tools() != null && request.tools().isArray() && !request.tools().isEmpty()) {
			ArrayNode toolsArray = UniversalToolNormalizer.toGeminiTools(request.tools(), objectMapper);
			if (toolsArray != null) {
				body.set("tools", toolsArray);
			}
		}

		if (request.toolChoice() != null && !request.toolChoice().isNull()) {
			ObjectNode toolConfig = UniversalToolNormalizer.toGeminiToolConfig(request.toolChoice(), objectMapper);
			if (toolConfig != null) {
				body.set("toolConfig", toolConfig);
			}
		}

		return objectMapper.writeValueAsString(body);
	}

	@Override
	public Map<String, String> buildRequestHeaders(ProviderConfig config) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		String key = config.apiKey() == null ? "" : config.apiKey().value();
		if (config.type() == ProviderType.VERTEX_AI) {
			headers.put("Authorization", "Bearer " + key);
		} else {
			headers.put("x-goog-api-key", key);
		}
		return headers;
	}

	@Override
	public SseNormalizer newNormalizer(boolean includeUsageInResponse, String fallbackModel) {
		return new GeminiSseNormalizer(objectMapper, fallbackModel, includeUsageInResponse);
	}

	private OpenAiChatRequest parse(String rawRequestBody) {
		return objectMapper.readValue(rawRequestBody, OpenAiChatRequest.class);
	}

	private void appendUserContentParts(@Nullable JsonNode content, ArrayNode parts) {
		if (content == null || content.isNull()) {
			return;
		}
		if (content.isString()) {
			parts.addObject().put("text", content.asString());
			return;
		}
		if (content.isArray()) {
			for (JsonNode part : content) {
				String type = part.path("type").asString("");
				if ("text".equals(type)) {
					parts.addObject().put("text", part.path("text").asString(""));
				} else if ("image_url".equals(type)) {
					String url = part.path("image_url").path("url").asString("");
					if (url.startsWith("data:") && url.contains(";base64,")) {
						int commaIdx = url.indexOf(',');
						String mimeType = url.substring(5, url.indexOf(';'));
						String b64 = url.substring(commaIdx + 1);
						ObjectNode inlineData = parts.addObject().putObject("inlineData");
						inlineData.put("mimeType", mimeType);
						inlineData.put("data", b64);
					}
				}
			}
		}
	}

	private @Nullable String messageText(@Nullable JsonNode content) {
		if (content == null || content.isNull()) {
			return null;
		}
		if (content.isString()) {
			return content.asString();
		}
		if (content.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode part : content) {
				if ("text".equals(part.path("type").asString(""))) {
					if (!sb.isEmpty()) {
						sb.append('\n');
					}
					sb.append(part.path("text").asString(""));
				}
			}
			return sb.isEmpty() ? null : sb.toString();
		}
		return null;
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

	private static String stripTrailingSlash(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}
}
