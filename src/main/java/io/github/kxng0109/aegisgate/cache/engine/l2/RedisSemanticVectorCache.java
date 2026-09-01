package io.github.kxng0109.aegisgate.cache.engine.l2;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.cache.engine.CacheGuardrails;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.aegisgate.proxy.embeddings.VectorEncodingUtils;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2 Semantic Vector Cache backed by RediSearch / Redis Vector Similarity Search (VSS).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSemanticVectorCache {

	public static final String INDEX_NAME = "aegis:cache:idx";
	public static final String PREFIX = "aegis:cache:doc:";
	private static final int DEFAULT_VECTOR_DIMENSIONS = 1536;

	private final RediSearchVectorClient vectorClient;
	private final EmbeddingService embeddingService;
	private final CacheGuardrails guardrails;
	private final AegisCacheProperties properties;

	/**
	 * Initializes the RediSearch index on startup.
	 */
	public void initializeIndex() {
		try {
			vectorClient.createIndexIfNotExists(INDEX_NAME, PREFIX, DEFAULT_VECTOR_DIMENSIONS);
		} catch (Exception ex) {
			log.warn("Non-fatal RediSearch index initialization warning: {}", ex.getMessage());
		}
	}

	/**
	 * Evaluates whether an semantically equivalent completion exists in the L2 vector index.
	 *
	 * @param key compound partition key
	 * @return cached entry if similarity meets threshold and passes guardrails, null otherwise
	 */
	public @Nullable CacheEntry findSemanticMatch(CompoundCacheKey key) {
		if (!properties.getSemantic().isEnabled() || key.promptText().isBlank()) {
			return null;
		}

		float[] queryVector = generateEmbedding(key.promptText(), key.ownerId());
		if (queryVector == null || queryVector.length == 0) {
			return null;
		}

		String filterQuery = buildFilterQuery(key);
		List<VectorSearchResult> results = vectorClient.searchKnn(INDEX_NAME, filterQuery, queryVector, 1);
		if (results.isEmpty()) {
			return null;
		}

		VectorSearchResult bestMatch = results.getFirst();
		float score = bestMatch.similarityScore();
		double threshold = properties.getSemantic().getSimilarityThreshold();

		if (score < threshold) {
			log.debug("L2 semantic candidate below threshold: score={}, threshold={}", score, threshold);
			return null;
		}

		String cachedPrompt = bestMatch.fields().getOrDefault("prompt_text", "");
		boolean passed = guardrails.validateSemanticMatch(
				key.promptText(),
				cachedPrompt,
				properties.getSemantic().isPolarityGuardEnabled(),
				properties.getSemantic().isEntityGuardEnabled()
		);

		if (!passed) {
			log.debug(
					"L2 semantic candidate failed guardrail checks: incoming='{}', cached='{}'",
					key.promptText(),
					cachedPrompt
			);
			return null;
		}

		return mapToCacheEntry(bestMatch, score, key);
	}

	/**
	 * Saves a generated completion and its prompt embedding into the L2 vector index.
	 *
	 * @param key              compound partition key
	 * @param responseJson     completion JSON payload
	 * @param promptTokens     tokens in prompt
	 * @param completionTokens tokens in completion
	 * @param totalTokens      total tokens
	 * @param ttl              time-to-live duration
	 */
	public void storeSemanticEntry(
			CompoundCacheKey key,
			String responseJson,
			int promptTokens,
			int completionTokens,
			int totalTokens,
			Duration ttl
	) {
		if (!properties.getSemantic().isEnabled() || key.promptText().isBlank()) {
			return;
		}

		float[] vector = generateEmbedding(key.promptText(), key.ownerId());
		if (vector == null || vector.length == 0) {
			return;
		}

		String entryId = UUID.randomUUID().toString().replace("-", "");
		String docKey = key.toVectorDocRedisKey(entryId);

		Map<byte[], byte[]> fields = new HashMap<>();
		fields.put("owner_id".getBytes(StandardCharsets.UTF_8), key.ownerId().getBytes(StandardCharsets.UTF_8));
		fields.put("model".getBytes(StandardCharsets.UTF_8), key.model().getBytes(StandardCharsets.UTF_8));
		fields.put("prefix_hash".getBytes(StandardCharsets.UTF_8), key.prefixHash().getBytes(StandardCharsets.UTF_8));
		fields.put(
				"system_prompt_hash".getBytes(StandardCharsets.UTF_8),
				key.systemPromptHash().getBytes(StandardCharsets.UTF_8)
		);
		fields.put("prompt_text".getBytes(StandardCharsets.UTF_8), key.promptText().getBytes(StandardCharsets.UTF_8));
		fields.put("response_json".getBytes(StandardCharsets.UTF_8), responseJson.getBytes(StandardCharsets.UTF_8));
		fields.put(
				"prompt_tokens".getBytes(StandardCharsets.UTF_8),
				String.valueOf(promptTokens).getBytes(StandardCharsets.UTF_8)
		);
		fields.put(
				"completion_tokens".getBytes(StandardCharsets.UTF_8),
				String.valueOf(completionTokens).getBytes(StandardCharsets.UTF_8)
		);
		fields.put(
				"total_tokens".getBytes(StandardCharsets.UTF_8),
				String.valueOf(totalTokens).getBytes(StandardCharsets.UTF_8)
		);
		fields.put(
				"created_at".getBytes(StandardCharsets.UTF_8),
				Instant.now().toString().getBytes(StandardCharsets.UTF_8)
		);
		fields.put("embedding".getBytes(StandardCharsets.UTF_8), VectorEncodingUtils.floatsToLittleEndianBytes(vector));

		vectorClient.saveVectorDocument(docKey, fields, ttl);
	}

	private @Nullable float[] generateEmbedding(String text, String ownerId) {
		String embeddingModel = properties.getSemantic().getEmbeddingModel();
		EmbeddingRequest request = new EmbeddingRequest(List.of(text), embeddingModel, null, null, null);
		try {
			EmbeddingResponse response = embeddingService.processEmbedding(request, ownerId);
			if (response.data() != null && !response.data().isEmpty()) {
				EmbeddingData data = response.data().getFirst();
				return extractFloatVector(data.embedding());
			}
		} catch (Exception ex) {
			log.warn("Embedding generation failed for semantic cache lookup: {}", ex.getMessage());
		}
		return null;
	}

	private @Nullable float[] extractFloatVector(@Nullable Object embedding) {
		if (embedding == null) {
			return null;
		}
		if (embedding instanceof float[] floats) {
			return floats;
		}
		if (embedding instanceof String base64) {
			return VectorEncodingUtils.decodeFromBase64(base64);
		}
		if (embedding instanceof List<?> list) {
			float[] vector = new float[list.size()];
			for (int i = 0; i < list.size(); i++) {
				Object elem = list.get(i);
				if (elem instanceof Number num) {
					vector[i] = num.floatValue();
				}
			}
			return vector;
		}
		return null;
	}

	private String buildFilterQuery(CompoundCacheKey key) {
		StringBuilder sb = new StringBuilder();
		sb.append("@owner_id:{").append(RediSearchVectorClient.escapeTag(key.ownerId())).append("} ");
		sb.append("@model:{").append(RediSearchVectorClient.escapeTag(key.model())).append("}");

		if (!key.prefixHash().isBlank()) {
			sb.append(" @prefix_hash:{").append(RediSearchVectorClient.escapeTag(key.prefixHash())).append("}");
		}
		if (!key.systemPromptHash().isBlank()) {
			sb.append(" @system_prompt_hash:{").append(RediSearchVectorClient.escapeTag(key.systemPromptHash()))
			  .append("}");
		}

		return sb.toString();
	}

	private CacheEntry mapToCacheEntry(VectorSearchResult match, float score, CompoundCacheKey key) {
		Map<String, String> fields = match.fields();
		String responseJson = fields.getOrDefault("response_json", "");
		int promptTokens = parseInt(fields.get("prompt_tokens"), 0);
		int completionTokens = parseInt(fields.get("completion_tokens"), 0);
		int totalTokens = parseInt(fields.get("total_tokens"), promptTokens + completionTokens);

		Instant createdAt = Instant.now();
		String createdAtStr = fields.get("created_at");
		if (createdAtStr != null) {
			try {
				createdAt = Instant.parse(createdAtStr);
			} catch (Exception ignored) {
			}
		}

		return new CacheEntry(
				match.docKey(),
				key.ownerId(),
				key.scope(),
				key.model(),
				fields.getOrDefault("prompt_text", key.promptText()),
				key.systemPromptHash(),
				key.prefixHash(),
				responseJson,
				promptTokens,
				completionTokens,
				totalTokens,
				createdAt,
				score
		);
	}

	private int parseInt(@Nullable String val, int fallback) {
		if (val == null || val.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
