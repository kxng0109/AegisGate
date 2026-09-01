package io.github.kxng0109.aegisgate.proxy.embeddings;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Normalized result extracted from an upstream embedding provider response.
 *
 * @param vectors       dense vector float arrays
 * @param promptTokens  total evaluated prompt tokens for this sub-batch
 * @param base64Vectors optional pre-encoded Base64 vector strings
 */
public record NormalizedEmbeddingResult(
		List<float[]> vectors,
		int promptTokens,
		@Nullable List<String> base64Vectors
) {

	public static NormalizedEmbeddingResult ofFloats(List<float[]> vectors, int promptTokens) {
		return new NormalizedEmbeddingResult(vectors, promptTokens, null);
	}

	public static NormalizedEmbeddingResult ofBase64(List<String> base64Vectors, List<float[]> vectors, int promptTokens) {
		return new NormalizedEmbeddingResult(vectors, promptTokens, base64Vectors);
	}
}
