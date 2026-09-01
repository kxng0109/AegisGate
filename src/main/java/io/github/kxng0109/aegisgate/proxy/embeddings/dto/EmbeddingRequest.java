package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard OpenAI-compatible embedding request representation.
 *
 * @param input          polymorphic input payload (single string, array of strings, or token ID arrays)
 * @param model          requested model identifier
 * @param dimensions     optional Matryoshka dimension truncation limit
 * @param encodingFormat optional encoding format ("float" or "base64", defaults to "float")
 * @param user           optional end-user tracking identifier
 */
@Schema(name = "EmbeddingRequest", description = "OpenAI-compatible vector embedding request payload")
public record EmbeddingRequest(
		@Schema(description = "Input text to embed (single string or array of strings)", example = "[\"First text to embed\", \"Second text to embed\"]", requiredMode = Schema.RequiredMode.REQUIRED)
		@JsonProperty("input") Object input,

		@Schema(description = "Configured model alias identifier", example = "text-embedding-3-small", requiredMode = Schema.RequiredMode.REQUIRED)
		@JsonProperty("model") String model,

		@Schema(description = "Optional output dimension count for Matryoshka models", example = "512")
		@JsonProperty("dimensions") @Nullable Integer dimensions,

		@Schema(description = "Encoding format: float (default) or base64 binary Little-Endian IEEE 754", example = "float")
		@JsonProperty("encoding_format") @Nullable String encodingFormat,

		@Schema(description = "Optional end-user identifier for abuse monitoring", example = "user-12345")
		@JsonProperty("user") @Nullable String user
) {

	/**
	 * Extracts the input elements as a normalized list of text strings.
	 *
	 * @return list of input text strings to embed
	 */
	public List<String> extractTextInputs() {
		if (input == null) {
			return List.of();
		}
		if (input instanceof String s) {
			return List.of(s);
		}
		if (input instanceof List<?> list) {
			if (list.isEmpty()) {
				return List.of();
			}
			// Check if this is a single token ID array: List<Integer>
			if (list.getFirst() instanceof Number) {
				return List.of(list.toString());
			}
			List<String> result = new ArrayList<>(list.size());
			for (Object item : list) {
				if (item instanceof String s) {
					result.add(s);
				} else if (item != null) {
					result.add(item.toString());
				}
			}
			return result;
		}
		return List.of(input.toString());
	}

	/**
	 * Returns {@code true} if the client explicitly requested Base64 binary float encoding.
	 */
	public boolean isBase64Requested() {
		return "base64".equalsIgnoreCase(encodingFormat);
	}
}
