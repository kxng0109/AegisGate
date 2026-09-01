package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage report for an embedding request.
 *
 * @param promptTokens total prompt tokens consumed
 * @param totalTokens  total tokens consumed (identical to promptTokens for embedding workloads)
 */
public record EmbeddingUsage(
		@JsonProperty("prompt_tokens") int promptTokens,
		@JsonProperty("total_tokens") int totalTokens
) {

	public static EmbeddingUsage of(int promptTokens) {
		return new EmbeddingUsage(promptTokens, promptTokens);
	}
}
