package io.github.kxng0109.aegisgate.contracts;

/**
 * The API dialect spoken by an upstream provider.
 *
 * <p>The gateway exposes a single OpenAI shaped endpoint to clients; each
 * provider type tells the gateway which native protocol to use when talking to that upstream. Protocol translation
 * itself lands in a later phase; here the type is carried by {@link ProviderConfig} so the routing layer knows what it
 * is dealing with.</p>
 */
public enum ProviderType {

	/**
	 * OpenAI compatible API ({@code /v1/chat/completions}, SSE chunks, {@code Authorization: Bearer}).
	 */
	OPENAI,

	/**
	 * Anthropic Messages API ({@code /v1/messages}, typed SSE events, {@code x-api-key}).
	 */
	ANTHROPIC,

	/**
	 * Local Ollama chat API ({@code /api/chat}, NDJSON lines, no auth).
	 */
	OLLAMA
}