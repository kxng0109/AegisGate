package io.github.kxng0109.aegisgate.proxy.embeddings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public record EmbeddingRequest(
		@JsonProperty("input") Object input,
		@JsonProperty("model") String model,
		@JsonProperty("dimensions") @Nullable Integer dimensions,
		@JsonProperty("encoding_format") @Nullable String encodingFormat,
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
