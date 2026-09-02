package io.github.kxng0109.aegisgate.proxy.protocol;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds OpenAI shaped SSE lines for the normalized providers.
 *
 * <p>Anthropic, Gemini, DeepSeek, and Ollama streams are rewritten into the exact chunk shape a
 * client of the OpenAI chat completions endpoint expects: a shared chunk id, a delta carrying content,
 * reasoning deltas, tool call chunks, final chunk with finish reason, usage chunk, and the {@code [DONE]} marker.</p>
 */
final class OpenAiSseLine {

	/**
	 * The terminal SSE marker every OpenAI stream ends with.
	 */
	static final String DONE = "data: [DONE]";

	private OpenAiSseLine() {
	}

	/**
	 * A chunk carrying one content delta.
	 */
	static String delta(ObjectMapper mapper, String id, long created, String model, String text) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		root.withArray("choices").addObject()
		    .put("index", 0)
		    .putObject("delta")
		    .put("content", text);
		return dataLine(root);
	}

	/**
	 * A chunk carrying reasoning tokens (Chain-of-Thought) from thinking models (DeepSeek, Gemini).
	 */
	static String reasoningDelta(ObjectMapper mapper, String id, long created, String model, String reasoningText) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		root.withArray("choices").addObject()
		    .put("index", 0)
		    .putObject("delta")
		    .put("reasoning_content", reasoningText);
		return dataLine(root);
	}

	/**
	 * A chunk starting a tool call declaration with name and optional initial arguments.
	 */
	static String toolCallHeader(ObjectMapper mapper, String id, long created, String model,
	                             int toolIndex, String toolId, String functionName, @Nullable String initialArgs) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		ObjectNode choice = root.withArray("choices").addObject();
		choice.put("index", 0);
		ArrayNode toolCalls = choice.putObject("delta").putArray("tool_calls");
		ObjectNode toolCall = toolCalls.addObject();
		toolCall.put("index", toolIndex);
		if (toolId != null && !toolId.isBlank()) {
			toolCall.put("id", toolId);
		}
		toolCall.put("type", "function");
		ObjectNode func = toolCall.putObject("function");
		func.put("name", functionName);
		func.put("arguments", initialArgs != null ? initialArgs : "");
		return dataLine(root);
	}

	/**
	 * A chunk streaming incremental JSON arguments for an active tool call.
	 */
	static String toolCallArgumentDelta(ObjectMapper mapper, String id, long created, String model,
	                                    int toolIndex, String argumentChunk) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		ObjectNode choice = root.withArray("choices").addObject();
		choice.put("index", 0);
		ArrayNode toolCalls = choice.putObject("delta").putArray("tool_calls");
		ObjectNode toolCall = toolCalls.addObject();
		toolCall.put("index", toolIndex);
		toolCall.putObject("function").put("arguments", argumentChunk);
		return dataLine(root);
	}

	/**
	 * The final chunk carrying the default finish reason ("stop") and an empty delta.
	 */
	static String finished(ObjectMapper mapper, String id, long created, String model) {
		return finishedWithReason(mapper, id, created, model, "stop");
	}

	/**
	 * The final chunk carrying a specific finish reason (e.g. "stop", "tool_calls", "length", "content_filter").
	 */
	static String finishedWithReason(ObjectMapper mapper, String id, long created, String model, String finishReason) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		ObjectNode choice = root.withArray("choices").addObject();
		choice.put("index", 0);
		choice.putObject("delta");
		choice.put("finish_reason", finishReason != null ? finishReason : "stop");
		return dataLine(root);
	}

	/**
	 * The usage chunk streamed before {@code [DONE]} when the client asked for it.
	 */
	static String usage(ObjectMapper mapper, String id, long created, String model,
	                    long promptTokens, long completionTokens) {
		return usageWithDetails(mapper, id, created, model, promptTokens, completionTokens, 0L, 0L);
	}

	/**
	 * Enhanced usage chunk including prompt cache hit details and reasoning token counts.
	 */
	static String usageWithDetails(ObjectMapper mapper, String id, long created, String model,
	                               long promptTokens, long completionTokens,
	                               long cachedTokens, long reasoningTokens) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		root.putArray("choices");
		ObjectNode usage = root.putObject("usage");
		usage.put("prompt_tokens", promptTokens);
		usage.put("completion_tokens", completionTokens);
		usage.put("total_tokens", promptTokens + completionTokens);
		if (cachedTokens > 0) {
			usage.putObject("prompt_tokens_details").put("cached_tokens", cachedTokens);
		}
		if (reasoningTokens > 0) {
			usage.putObject("completion_tokens_details").put("reasoning_tokens", reasoningTokens);
		}
		return dataLine(root);
	}

	private static ObjectNode baseChunk(ObjectMapper mapper, String id, long created, String model) {
		ObjectNode root = mapper.createObjectNode();
		root.put("id", id);
		root.put("object", "chat.completion.chunk");
		root.put("created", created);
		root.put("model", model);
		return root;
	}

	private static String dataLine(ObjectNode root) {
		return "data: " + root;
	}
}
