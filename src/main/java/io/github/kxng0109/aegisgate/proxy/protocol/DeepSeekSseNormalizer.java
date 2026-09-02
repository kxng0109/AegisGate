package io.github.kxng0109.aegisgate.proxy.protocol;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Normalizes streaming Server-Sent Events from the DeepSeek API into canonical OpenAI chunks, preserving
 * {@code reasoning_content} and prompt cache hit telemetry.
 */
@Slf4j
public final class DeepSeekSseNormalizer implements SseNormalizer {

	private static final String DATA_PREFIX = "data:";
	private static final String DONE_SENTINEL = "[DONE]";

	private final ObjectMapper objectMapper;
	private final String fallbackModel;
	private final boolean includeUsageInResponse;

	private @Nullable Long promptTokens;
	private @Nullable Long completionTokens;
	private @Nullable Long cachedTokens;
	private @Nullable Long reasoningTokens;
	private @Nullable String upstreamModel;
	private boolean done;

	/**
	 * Creates a new DeepSeek SSE normalizer.
	 *
	 * @param objectMapper           JSON object mapper
	 * @param fallbackModel          fallback model identifier
	 * @param includeUsageInResponse whether to relay usage chunk
	 */
	public DeepSeekSseNormalizer(ObjectMapper objectMapper, String fallbackModel, boolean includeUsageInResponse) {
		this.objectMapper = objectMapper;
		this.fallbackModel = fallbackModel;
		this.includeUsageInResponse = includeUsageInResponse;
	}

	@Override
	public List<String> normalizeLine(String rawLine) {
		if (done) {
			return List.of();
		}
		String trimmed = rawLine.trim();
		if (trimmed.isEmpty() || !trimmed.startsWith(DATA_PREFIX)) {
			return List.of();
		}

		String payload = trimmed.substring(DATA_PREFIX.length()).trim();
		if (DONE_SENTINEL.equals(payload)) {
			done = true;
			return List.of(OpenAiSseLine.DONE);
		}

		JsonNode node = parse(payload);
		if (node == null || !node.isObject()) {
			return List.of();
		}

		if (node.has("model") && node.get("model").isString()) {
			upstreamModel = node.get("model").asString();
		}

		JsonNode choices = node.path("choices");
		if (node.has("usage") && node.get("usage").isObject()) {
			JsonNode usageNode = node.get("usage");
			promptTokens = usageNode.path("prompt_tokens").asLong(promptTokens != null ? promptTokens : 0L);
			completionTokens = usageNode.path("completion_tokens")
			                            .asLong(completionTokens != null ? completionTokens : 0L);
			cachedTokens = usageNode.path("prompt_cache_hit_tokens").asLong(cachedTokens != null ? cachedTokens : 0L);
			if (usageNode.has("completion_tokens_details")) {
				reasoningTokens = usageNode.path("completion_tokens_details").path("reasoning_tokens").asLong(0L);
			}

			// Standalone usage chunk (no choices or empty choices)
			if ((choices.isMissingNode() || (choices.isArray() && choices.isEmpty())) && !includeUsageInResponse) {
				return List.of();
			}
		}

		return List.of("data: " + payload);
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public @Nullable UsageInfo usage() {
		if (promptTokens == null && completionTokens == null) {
			return null;
		}
		return new UsageInfo(
				promptTokens != null ? promptTokens : 0L,
				completionTokens != null ? completionTokens : 0L
		);
	}

	@Override
	public @Nullable String upstreamModel() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	/**
	 * Returns prompt cache hit tokens observed from DeepSeek's usage object.
	 *
	 * @return cached token count or null if not reported
	 */
	public @Nullable Long cachedTokens() {
		return cachedTokens;
	}

	/**
	 * Returns reasoning tokens observed from DeepSeek's thinking phase.
	 *
	 * @return reasoning token count or null if not reported
	 */
	public @Nullable Long reasoningTokens() {
		return reasoningTokens;
	}

	private @Nullable JsonNode parse(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.debug("Ignoring unparseable DeepSeek SSE chunk: {}", json);
			return null;
		}
	}
}
