package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Single dense embedding object representation matching the OpenAI wire schema.
 *
 * @param object    constant schema identifier ("embedding")
 * @param index     0-based integer position in the original request batch
 * @param embedding vector data represented as either a primitive float array ({@code float[]}) or a Base64 string
 */
@Schema(name = "EmbeddingData", description = "Single dense vector embedding result item")
public record EmbeddingData(
		@Schema(description = "Object type (always 'embedding')", example = "embedding")
		@JsonProperty("object") String object,

		@Schema(description = "0-based position index in original input batch", example = "0")
		@JsonProperty("index") int index,

		@Schema(description = "Dense float vector array or Base64 encoded binary string")
		@JsonProperty("embedding") Object embedding
) {
	public static EmbeddingData of(int index, float[] vector) {
		return new EmbeddingData("embedding", index, vector);
	}

	public static EmbeddingData of(int index, String base64) {
		return new EmbeddingData("embedding", index, base64);
	}
}
