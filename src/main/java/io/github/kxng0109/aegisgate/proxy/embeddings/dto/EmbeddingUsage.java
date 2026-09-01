package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token usage report for an embedding request.
 *
 * @param promptTokens total prompt tokens consumed
 * @param totalTokens  total tokens consumed (identical to promptTokens for embedding workloads)
 */
@Schema(name = "EmbeddingUsage", description = "Token consumption report for an embedding request")
public record EmbeddingUsage(
		@Schema(description = "Input prompt tokens consumed", example = "8")
		@JsonProperty("prompt_tokens") int promptTokens,

		@Schema(description = "Total tokens billed", example = "8")
		@JsonProperty("total_tokens") int totalTokens
) {
	public static EmbeddingUsage of(int promptTokens) {
		return new EmbeddingUsage(promptTokens, promptTokens);
	}
}
