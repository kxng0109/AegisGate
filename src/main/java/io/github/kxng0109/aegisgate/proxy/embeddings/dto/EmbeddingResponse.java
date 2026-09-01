package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Standard OpenAI-compatible embedding response envelope.
 *
 * @param object constant collection identifier ("list")
 * @param data   sequentially ordered list of embedding objects
 * @param model  model identifier utilized
 * @param usage  token usage metrics
 */
public record EmbeddingResponse(
		@JsonProperty("object") String object,
		@JsonProperty("data") List<EmbeddingData> data,
		@JsonProperty("model") String model,
		@JsonProperty("usage") EmbeddingUsage usage
) {

	public static EmbeddingResponse of(String model, List<EmbeddingData> data, int promptTokens) {
		return new EmbeddingResponse("list", data, model, EmbeddingUsage.of(promptTokens));
	}
}
