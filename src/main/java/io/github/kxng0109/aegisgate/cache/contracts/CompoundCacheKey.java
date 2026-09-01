package io.github.kxng0109.aegisgate.cache.contracts;

/**
 * Immutable cryptographic compound partition coordinates identifying a request's cache position.
 *
 * @param ownerId          tenant identifier
 * @param scope            isolation scope
 * @param model            model alias
 * @param exactHash        SHA-256 digest of normalized request payload for L1 exact matching
 * @param prefixHash       SHA-256 digest of conversation history prefix for L2 multi-turn matching
 * @param systemPromptHash SHA-256 digest of system instructions
 * @param promptText       normalized user prompt text for L2 vectorization
 */
public record CompoundCacheKey(
		String ownerId,
		CacheScope scope,
		String model,
		String exactHash,
		String prefixHash,
		String systemPromptHash,
		String promptText
) {
	/**
	 * Returns the Redis key for L1 exact key-value storage.
	 *
	 * @return Redis exact match key
	 */
	public String toExactRedisKey() {
		return "aegis:cache:exact:" + ownerId + ":" + exactHash;
	}

	/**
	 * Returns the Redis key for L2 vector document storage.
	 *
	 * @param entryId unique entry identifier
	 * @return Redis vector document key
	 */
	public String toVectorDocRedisKey(String entryId) {
		return "aegis:cache:doc:" + ownerId + ":" + entryId;
	}
}
