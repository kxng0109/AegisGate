package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Standard OpenAI-compatible embedding response envelope.
 *
 * @param object constant collection identifier ("list")
 * @param data   sequentially ordered list of embedding objects
 * @param model  model identifier utilized
 * @param usage  token usage metrics
 */
@Schema(name = "EmbeddingResponse", description = "Standard OpenAI-compatible vector embeddings response envelope")
public record EmbeddingResponse(
		@Schema(description = "Object type (always 'list')", example = "list")
		@JsonProperty("object") String object,

		@Schema(description = "Ordered list of embedding vectors")
		@JsonProperty("data") List<EmbeddingData> data,

		@Schema(description = "Model alias utilized", example = "text-embedding-3-small")
		@JsonProperty("model") String model,

		@Schema(description = "Token consumption metadata")
		@JsonProperty("usage") EmbeddingUsage usage
) {
	public static EmbeddingResponse of(String model, List<EmbeddingData> data, int promptTokens) {
		return new EmbeddingResponse("list", data, model, EmbeddingUsage.of(promptTokens));
	}
}
