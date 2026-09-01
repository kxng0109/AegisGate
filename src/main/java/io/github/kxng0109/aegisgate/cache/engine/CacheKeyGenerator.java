package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Generates deterministic cryptographic compound partition keys for L1 exact matching and L2 semantic vector search.
 */
@Component
public class CacheKeyGenerator {

	private static final HexFormat HEX = HexFormat.of();

	/**
	 * Extracts the active user prompt text from the request.
	 *
	 * @param request client chat request
	 * @return text content of the latest user message, or empty string
	 */
	public String extractUserPrompt(OpenAiChatRequest request) {
		List<OpenAiChatRequest.Message> messages = request.messages();
		for (int i = messages.size() - 1; i >= 0; i--) {
			OpenAiChatRequest.Message msg = messages.get(i);
			if (msg.role() != null && "user".equalsIgnoreCase(msg.role().trim())) {
				return extractText(msg.content());
			}
		}
		// Fallback to last message if role was omitted
		if (!messages.isEmpty()) {
			return extractText(messages.getLast().content());
		}
		return "";
	}

	/**
	 * Extracts and concatenates all system or developer instructions from the request.
	 *
	 * @param request client chat request
	 * @return concatenated system prompt text
	 */
	public String extractSystemPrompt(OpenAiChatRequest request) {
		StringBuilder sb = new StringBuilder();
		for (OpenAiChatRequest.Message msg : request.messages()) {
			if (msg.role() != null && ("system".equalsIgnoreCase(msg.role().trim())
					|| "developer".equalsIgnoreCase(msg.role().trim()))) {
				String text = extractText(msg.content());
				if (!text.isBlank()) {
					if (!sb.isEmpty()) {
						sb.append("\n");
					}
					sb.append(text);
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Computes a SHA-256 hash of prior conversation turns (messages 0 .. N-2) to partition multi-turn cache lookups.
	 *
	 * @param request          client chat request
	 * @param maxTurnCountback maximum number of trailing prior turns to include in the prefix
	 * @return SHA-256 hex digest of conversation prefix, or empty string for single-turn requests
	 */
	public String computePrefixHash(OpenAiChatRequest request, int maxTurnCountback) {
		List<OpenAiChatRequest.Message> messages = request.messages();
		if (messages.size() <= 1) {
			return "";
		}

		// Find the index of the active user message (the last user message)
		int lastUserIdx = -1;
		for (int i = messages.size() - 1; i >= 0; i--) {
			OpenAiChatRequest.Message msg = messages.get(i);
			if (msg.role() != null && "user".equalsIgnoreCase(msg.role().trim())) {
				lastUserIdx = i;
				break;
			}
		}

		if (lastUserIdx <= 0) {
			return "";
		}

		int nonSystemTurnsBefore = 0;
		for (int i = 0; i < lastUserIdx; i++) {
			OpenAiChatRequest.Message msg = messages.get(i);
			if (msg.role() != null && !msg.role().equalsIgnoreCase("system") && !msg.role()
			                                                                        .equalsIgnoreCase("developer")) {
				nonSystemTurnsBefore++;
			}
		}
		if (nonSystemTurnsBefore == 0) {
			return "";
		}

		int startIdx = Math.max(0, lastUserIdx - Math.max(1, maxTurnCountback));
		StringBuilder sb = new StringBuilder();
		for (int i = startIdx; i < lastUserIdx; i++) {
			OpenAiChatRequest.Message msg = messages.get(i);
			String role = msg.role() == null ? "unknown" : msg.role().trim().toLowerCase(Locale.ROOT);
			String text = extractText(msg.content());
			sb.append("[").append(role).append(":").append(text).append("]");
		}

		return sb.isEmpty() ? "" : sha256Hex(sb.toString());
	}

	/**
	 * Builds a complete {@link CompoundCacheKey} combining tenant scope, exact digest, prefix digest, system prompt
	 * digest, and active prompt text.
	 *
	 * @param request          client chat request
	 * @param ownerId          tenant identifier
	 * @param scope            isolation scope
	 * @param userId           optional end-user identifier for USER-scoped caching
	 * @param maxTurnCountback maximum turn countback for multi-turn prefix hashing
	 * @return compound cache key
	 */
	public CompoundCacheKey generateKey(
			OpenAiChatRequest request,
			String ownerId,
			CacheScope scope,
			@Nullable String userId,
			int maxTurnCountback
	) {
		String effectiveOwner = (scope == CacheScope.USER && userId != null && !userId.isBlank())
				? ownerId + ":" + userId
				: (scope == CacheScope.GLOBAL ? "global" : ownerId);

		String userPrompt = extractUserPrompt(request);
		String systemPrompt = extractSystemPrompt(request);
		String systemPromptHash = systemPrompt.isBlank() ? "" : sha256Hex(systemPrompt);
		String prefixHash = computePrefixHash(request, maxTurnCountback);

		String exactHash = computeExactHash(request, effectiveOwner);

		return new CompoundCacheKey(
				effectiveOwner,
				scope,
				request.model(),
				exactHash,
				prefixHash,
				systemPromptHash,
				userPrompt
		);
	}

	/**
	 * Computes the canonical SHA-256 digest for L1 exact matching.
	 *
	 * @param request        client chat request
	 * @param effectiveOwner effective owner or user namespace
	 * @return SHA-256 hex digest
	 */
	public String computeExactHash(OpenAiChatRequest request, String effectiveOwner) {
		StringBuilder sb = new StringBuilder();
		sb.append("owner=").append(effectiveOwner).append(";");
		sb.append("model=").append(request.model()).append(";");
		sb.append("temp=")
		  .append(request.temperature() == null ? "default" : String.format(Locale.ROOT, "%.2f", request.temperature()))
		  .append(";");
		sb.append("top_p=")
		  .append(request.topP() == null ? "default" : String.format(Locale.ROOT, "%.2f", request.topP())).append(";");
		sb.append("max_tokens=").append(request.effectiveMaxTokens() == null ? "default" : request.effectiveMaxTokens())
		  .append(";");

		sb.append("messages=[");
		for (OpenAiChatRequest.Message msg : request.messages()) {
			String role = msg.role() == null ? "unknown" : msg.role().trim().toLowerCase(Locale.ROOT);
			String text = extractText(msg.content());
			sb.append("{r:").append(role).append(",c:").append(text).append("},");
		}
		sb.append("]");

		return sha256Hex(sb.toString());
	}

	/**
	 * Extracts string text from a Jackson JsonNode representing a message's content.
	 *
	 * @param content message content node
	 * @return extracted plain text
	 */
	public String extractText(@Nullable JsonNode content) {
		if (content == null || content.isNull()) {
			return "";
		}
		if (content.isTextual()) {
			return content.asText();
		}
		if (content.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : content) {
				if (item.isTextual()) {
					sb.append(item.asText());
				} else if (item.isObject()) {
					if (item.has("text") && item.get("text").isTextual()) {
						sb.append(item.get("text").asText());
					}
				}
			}
			return sb.toString();
		}
		return content.toString();
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			return HEX.formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 algorithm unavailable", e);
		}
	}
}
