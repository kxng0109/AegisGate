package io.github.kxng0109.aegisgate.cache.engine.l2;

import java.util.Map;

/**
 * Result from a RediSearch Vector Similarity Search query.
 *
 * @param docKey   Redis document key of the matched entry
 * @param distance vector distance score (Cosine distance: 0.0 = identical)
 * @param fields   metadata and payload fields returned from the document
 */
public record VectorSearchResult(
		String docKey,
		double distance,
		Map<String, String> fields
) {
	/**
	 * Computes the cosine similarity score from the cosine distance ($S_C = 1 - D$).
	 *
	 * @return cosine similarity in the range [-1.0, 1.0]
	 */
	public float similarityScore() {
		return (float) Math.max(-1.0, Math.min(1.0, 1.0 - distance));
	}
}
