package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single dense embedding object representation matching the OpenAI wire schema.
 *
 * @param object    constant schema identifier ("embedding")
 * @param index     0-based integer position in the original request batch
 * @param embedding vector data represented as either a primitive float array ({@code float[]}) or a Base64 string
 */
public record EmbeddingData(
		@JsonProperty("object") String object,
		@JsonProperty("index") int index,
		@JsonProperty("embedding") Object embedding
) {

	public static EmbeddingData of(int index, float[] vector) {
		return new EmbeddingData("embedding", index, vector);
	}

	public static EmbeddingData of(int index, String base64) {
		return new EmbeddingData("embedding", index, base64);
	}
}
