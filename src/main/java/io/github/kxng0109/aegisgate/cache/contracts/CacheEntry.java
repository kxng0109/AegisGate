package io.github.kxng0109.aegisgate.cache.contracts;

import java.time.Instant;

/**
 * Immutable canonical representation of a cached LLM completion document.
 *
 * @param id                  unique entry identifier (e.g., UUID or digest)
 * @param ownerId             tenant identifier owning this cached record
 * @param scope               isolation scope (TENANT, USER, GLOBAL)
 * @param model               client-facing model alias
 * @param promptText          normalized user prompt text
 * @param systemPromptHash    SHA-256 hash of system instructions (or empty)
 * @param prefixHash          SHA-256 hash of prior conversation turns (or empty)
 * @param responsePayloadJson canonical OpenAI-shaped completion JSON payload
 * @param promptTokens        number of prompt tokens consumed
 * @param completionTokens    number of completion tokens generated
 * @param totalTokens         total tokens
 * @param createdAt           timestamp when the entry was created
 * @param similarityScore     vector cosine similarity score (1.0 for exact matches)
 */
public record CacheEntry(
		String id,
		String ownerId,
		CacheScope scope,
		String model,
		String promptText,
		String systemPromptHash,
		String prefixHash,
		String responsePayloadJson,
		int promptTokens,
		int completionTokens,
		int totalTokens,
		Instant createdAt,
		float similarityScore
) {
	/**
	 * Creates a copy of this entry with an updated similarity score.
	 *
	 * @param score new similarity score
	 * @return new CacheEntry with updated similarity score
	 */
	public CacheEntry withSimilarityScore(float score) {
		return new CacheEntry(
				id,
				ownerId,
				scope,
				model,
				promptText,
				systemPromptHash,
				prefixHash,
				responsePayloadJson,
				promptTokens,
				completionTokens,
				totalTokens,
				createdAt,
				score
		);
	}
}
