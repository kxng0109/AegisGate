package io.github.kxng0109.aegisgate.proxy.protocol;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds OpenAI shaped SSE lines for the normalized providers.
 *
 * <p>Anthropic and Ollama streams are rewritten into the exact chunk shape a
 * client of the OpenAI chat completions endpoint expects: a shared chunk id, a delta carrying content, a final chunk
 * with an empty delta and the finish reason, an optional usage chunk with empty choices, and the {@code [DONE]} marker.
 * The helper exists so the three normalizers produce byte identical output.</p>
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
	 *
	 * @param mapper  the object mapper used to build the JSON
	 * @param id      shared chunk id for this stream
	 * @param created epoch seconds of the stream start
	 * @param model   model id reported by the provider
	 * @param text    the content delta
	 * @return the {@code data:} line
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
	 * The final chunk carrying the finish reason and an empty delta.
	 *
	 * @param mapper  the object mapper used to build the JSON
	 * @param id      shared chunk id for this stream
	 * @param created epoch seconds of the stream start
	 * @param model   model id reported by the provider
	 * @return the {@code data:} line
	 */
	static String finished(ObjectMapper mapper, String id, long created, String model) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		ObjectNode choice = root.withArray("choices").addObject();
		choice.put("index", 0);
		choice.putObject("delta");
		choice.put("finish_reason", "stop");
		return dataLine(root);
	}

	/**
	 * The usage chunk streamed before {@code [DONE]} when the client asked for it. Its choices array is empty, matching
	 * the OpenAI contract.
	 *
	 * @param mapper           the object mapper used to build the JSON
	 * @param id               shared chunk id for this stream
	 * @param created          epoch seconds of the stream start
	 * @param model            model id reported by the provider
	 * @param promptTokens     input tokens
	 * @param completionTokens output tokens
	 * @return the {@code data:} line
	 */
	static String usage(ObjectMapper mapper, String id, long created, String model,
	                    long promptTokens, long completionTokens) {
		ObjectNode root = baseChunk(mapper, id, created, model);
		root.putArray("choices");
		ObjectNode usage = root.putObject("usage");
		usage.put("prompt_tokens", promptTokens);
		usage.put("completion_tokens", completionTokens);
		usage.put("total_tokens", promptTokens + completionTokens);
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