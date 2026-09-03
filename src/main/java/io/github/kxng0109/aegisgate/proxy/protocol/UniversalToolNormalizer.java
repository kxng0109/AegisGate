package io.github.kxng0109.aegisgate.proxy.protocol;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Universal translator and normalizer for tool/function calling declarations, schema dialects, tool choices, and
 * conversational tool execution loops across OpenAI, Anthropic Claude, Google Gemini / Vertex AI, DeepSeek, and
 * Ollama.
 */
public final class UniversalToolNormalizer {

	private UniversalToolNormalizer() {
		// Utility class
	}

	/**
	 * Translates canonical OpenAI {@code tools} definitions into Anthropic {@code tools} format.
	 *
	 * <p>OpenAI schema:
	 * {@code [{"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}]}</p>
	 * <p>Anthropic schema: {@code [{"name": "...", "description": "...", "input_schema": {...}}]}</p>
	 *
	 * @param openAiTools  the tools array from the OpenAI request
	 * @param objectMapper JSON object mapper
	 * @return Anthropic formatted tools array, or {@code null} if empty
	 */
	public static @Nullable ArrayNode toAnthropicTools(@Nullable JsonNode openAiTools, ObjectMapper objectMapper) {
		if (openAiTools == null || !openAiTools.isArray() || openAiTools.isEmpty()) {
			return null;
		}
		ArrayNode anthropicTools = objectMapper.createArrayNode();
		for (JsonNode tool : openAiTools) {
			if (!tool.isObject()) {
				continue;
			}
			JsonNode func = tool.path("function");
			if (!func.isObject()) {
				continue;
			}
			String name = func.path("name").asString("");
			if (name.isBlank()) {
				continue;
			}
			ObjectNode outTool = anthropicTools.addObject();
			outTool.put("name", name);
			if (func.has("description") && !func.get("description").isNull()) {
				outTool.put("description", func.get("description").asString(""));
			}
			JsonNode parameters = func.path("parameters");
			if (parameters.isObject()) {
				outTool.set("input_schema", parameters.deepCopy());
			} else {
				outTool.set("input_schema", objectMapper.createObjectNode().put("type", "object"));
			}
		}
		return anthropicTools.isEmpty() ? null : anthropicTools;
	}

	/**
	 * Translates canonical OpenAI {@code tool_choice} and {@code parallel_tool_calls} into Anthropic
	 * {@code tool_choice}.
	 *
	 * @param toolChoice        OpenAI tool choice ("auto", "none", "required", or object)
	 * @param parallelToolCalls whether parallel tool calls are allowed
	 * @param objectMapper      JSON object mapper
	 * @return Anthropic formatted tool_choice object, or {@code null} if not applicable
	 */
	public static @Nullable ObjectNode toAnthropicToolChoice(@Nullable JsonNode toolChoice, @Nullable Boolean parallelToolCalls, ObjectMapper objectMapper) {
		if (toolChoice == null || toolChoice.isNull()) {
			return null;
		}
		ObjectNode out = objectMapper.createObjectNode();
		if (toolChoice.isString()) {
			String choice = toolChoice.asString();
			switch (choice) {
				case "auto" -> out.put("type", "auto");
				case "required" -> out.put("type", "any");
				case "none" -> out.put("type", "none");
				default -> out.put("type", "auto");
			}
		} else if (toolChoice.isObject()) {
			String type = toolChoice.path("type").asString("");
			if ("function".equals(type) && toolChoice.has("function")) {
				String name = toolChoice.path("function").path("name").asString("");
				if (!name.isBlank()) {
					out.put("type", "tool").put("name", name);
				}
			}
		}
		if (Boolean.FALSE.equals(parallelToolCalls)) {
			out.put("disable_parallel_tool_use", true);
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * Translates canonical OpenAI {@code tools} into Google Gemini / Vertex AI {@code tools[].functionDeclarations}.
	 *
	 * <p>Gemini mandates UPPERCASE OpenAPI 3.0 types (e.g. OBJECT, STRING, INTEGER, NUMBER, BOOLEAN, ARRAY).</p>
	 *
	 * @param openAiTools  the tools array from the OpenAI request
	 * @param objectMapper JSON object mapper
	 * @return Gemini formatted tools container array, or {@code null} if empty
	 */
	public static @Nullable ArrayNode toGeminiTools(@Nullable JsonNode openAiTools, ObjectMapper objectMapper) {
		if (openAiTools == null || !openAiTools.isArray() || openAiTools.isEmpty()) {
			return null;
		}
		ArrayNode declarations = objectMapper.createArrayNode();
		for (JsonNode tool : openAiTools) {
			if (!tool.isObject()) {
				continue;
			}
			JsonNode func = tool.path("function");
			if (!func.isObject()) {
				continue;
			}
			String name = func.path("name").asString("");
			if (name.isBlank()) {
				continue;
			}
			ObjectNode decl = declarations.addObject();
			decl.put("name", name);
			if (func.has("description") && !func.get("description").isNull()) {
				decl.put("description", func.get("description").asString(""));
			}
			JsonNode parameters = func.path("parameters");
			if (parameters.isObject()) {
				decl.set("parameters", toGeminiParameters(parameters, objectMapper));
			}
		}
		if (declarations.isEmpty()) {
			return null;
		}
		ArrayNode toolsContainer = objectMapper.createArrayNode();
		toolsContainer.addObject().set("functionDeclarations", declarations);
		return toolsContainer;
	}

	/**
	 * Recursively converts a JSON Schema object into Gemini OpenAPI UPPERCASE type format.
	 *
	 * @param schema       standard lowercase JSON Schema
	 * @param objectMapper JSON object mapper
	 * @return uppercase Gemini OpenAPI Schema Node
	 */
	public static ObjectNode toGeminiParameters(JsonNode schema, ObjectMapper objectMapper) {
		if (schema == null || !schema.isObject()) {
			return objectMapper.createObjectNode().put("type", "OBJECT");
		}
		ObjectNode out = objectMapper.createObjectNode();
		schema.properties().forEach(entry -> {
			String key = entry.getKey();
			JsonNode val = entry.getValue();
			if ("type".equals(key)) {
				if (val.isString()) {
					out.put("type", mapToGeminiType(val.asString()));
				} else {
					out.put("type", "OBJECT");
				}
			} else if ("properties".equals(key) && val.isObject()) {
				ObjectNode props = out.putObject("properties");
				val.properties().forEach(propEntry -> props.set(
						propEntry.getKey(),
						toGeminiParameters(propEntry.getValue(), objectMapper)
				));
			} else if ("items".equals(key) && val.isObject()) {
				out.set("items", toGeminiParameters(val, objectMapper));
			} else if ("required".equals(key) && val.isArray()) {
				out.set("required", val.deepCopy());
			} else if ("enum".equals(key) && val.isArray()) {
				out.set("enum", val.deepCopy());
			} else if ("description".equals(key) && val.isString()) {
				out.put("description", val.asString());
			} else {
				out.set(key, val.deepCopy());
			}
		});
		if (!out.has("type")) {
			out.put("type", "OBJECT");
		}
		return out;
	}

	/**
	 * Maps lowercase JSON schema types to uppercase OpenAPI types for Gemini.
	 */
	public static String mapToGeminiType(@Nullable String jsonType) {
		if (jsonType == null) {
			return "OBJECT";
		}
		return switch (jsonType.trim().toLowerCase()) {
			case "string" -> "STRING";
			case "integer" -> "INTEGER";
			case "number" -> "NUMBER";
			case "boolean" -> "BOOLEAN";
			case "array" -> "ARRAY";
			case "object" -> "OBJECT";
			default -> "OBJECT";
		};
	}

	/**
	 * Translates canonical OpenAI {@code tool_choice} into Gemini {@code toolConfig}.
	 *
	 * @param toolChoice   OpenAI tool choice directive
	 * @param objectMapper JSON object mapper
	 * @return Gemini toolConfig object, or {@code null} if not applicable
	 */
	public static @Nullable ObjectNode toGeminiToolConfig(@Nullable JsonNode toolChoice, ObjectMapper objectMapper) {
		if (toolChoice == null || toolChoice.isNull()) {
			return null;
		}
		ObjectNode toolConfig = objectMapper.createObjectNode();
		ObjectNode functionCallingConfig = toolConfig.putObject("functionCallingConfig");
		if (toolChoice.isString()) {
			String choice = toolChoice.asString();
			switch (choice) {
				case "auto" -> functionCallingConfig.put("mode", "AUTO");
				case "required" -> functionCallingConfig.put("mode", "ANY");
				case "none" -> functionCallingConfig.put("mode", "NONE");
				default -> functionCallingConfig.put("mode", "AUTO");
			}
		} else if (toolChoice.isObject()) {
			String type = toolChoice.path("type").asString("");
			if ("function".equals(type) && toolChoice.has("function")) {
				String name = toolChoice.path("function").path("name").asString("");
				if (!name.isBlank()) {
					functionCallingConfig.put("mode", "ANY");
					functionCallingConfig.putArray("allowedFunctionNames").add(name);
				}
			}
		}
		return toolConfig;
	}

	/**
	 * Generates a unique, stateless synthetic tool call ID for providers that omit them (e.g. Gemini, Ollama).
	 *
	 * @param functionName function name being called
	 * @param index        index of the tool call in the candidates
	 * @return synthetic tool call ID starting with {@code call_gen_}
	 */
	public static String generateSyntheticToolCallId(@Nullable String functionName, int index) {
		String rawUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		return "call_gen_" + (
				functionName != null ? functionName.substring(0, Math.min(8, functionName.length())) + "_" : "") + index
				+ "_" + rawUuid;
	}

	/**
	 * Normalizes tool execution result content into a valid JSON object for Google Gemini function responses.
	 *
	 * @param content      raw text or JSON string from {@code role: "tool"}
	 * @param objectMapper JSON object mapper
	 * @return structured JsonNode object
	 */
	public static JsonNode normalizeToolResultForGemini(@Nullable String content, ObjectMapper objectMapper) {
		if (content == null || content.isBlank()) {
			return objectMapper.createObjectNode().put("result", "");
		}
		try {
			JsonNode parsed = objectMapper.readTree(content);
			if (parsed.isObject()) {
				return parsed;
			}
		} catch (Exception ignored) {
			// Not valid JSON object; wrap in object
		}
		return objectMapper.createObjectNode().put("result", content);
	}
}
