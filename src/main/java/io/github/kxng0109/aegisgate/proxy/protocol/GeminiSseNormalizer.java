package io.github.kxng0109.aegisgate.proxy.protocol;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Normalizes streaming Server-Sent Events from Google Gemini and Google Cloud Vertex AI into canonical OpenAI-shaped
 * SSE chunks.
 *
 * <p>Handles incremental text generation, reasoning tokens ({@code thought: true}),
 * function calling declarations ({@code functionCall}), token usage metadata, and synthetic {@code [DONE]}
 * termination.</p>
 */
@Slf4j
public final class GeminiSseNormalizer implements SseNormalizer {

	private static final String DATA_PREFIX = "data:";

	private final ObjectMapper objectMapper;
	private final String chunkId;
	private final long created;
	private final String fallbackModel;
	private final boolean includeUsageInResponse;

	private @Nullable Long inputTokens;
	private @Nullable Long outputTokens;
	private @Nullable Long cachedTokens;
	private @Nullable Long reasoningTokens;
	private @Nullable String upstreamModel;
	private boolean hasToolCalls;
	private int toolCallIndex;
	private boolean done;

	/**
	 * @param objectMapper           JSON object mapper
	 * @param fallbackModel          fallback model identifier
	 * @param includeUsageInResponse whether to relay usage chunks
	 */
	public GeminiSseNormalizer(ObjectMapper objectMapper, String fallbackModel, boolean includeUsageInResponse) {
		this.objectMapper = objectMapper;
		this.chunkId = "chatcmpl-" + UUID.randomUUID();
		this.created = Instant.now().getEpochSecond();
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

		String json = trimmed.substring(DATA_PREFIX.length()).trim();
		if (json.isEmpty()) {
			return List.of();
		}

		JsonNode node = parse(json);
		if (node == null || !node.isObject()) {
			return List.of();
		}

		if (node.has("modelVersion") && node.get("modelVersion").isString()) {
			upstreamModel = node.get("modelVersion").asString();
		}

		if (node.has("usageMetadata") && node.get("usageMetadata").isObject()) {
			JsonNode usageNode = node.get("usageMetadata");
			inputTokens = usageNode.path("promptTokenCount").asLong(inputTokens != null ? inputTokens : 0L);
			outputTokens = usageNode.path("candidatesTokenCount").asLong(outputTokens != null ? outputTokens : 0L);
			cachedTokens = usageNode.path("cachedContentTokenCount").asLong(cachedTokens != null ? cachedTokens : 0L);
			reasoningTokens = usageNode.path("thoughtsTokenCount")
			                           .asLong(reasoningTokens != null ? reasoningTokens : 0L);
		}

		List<String> outputLines = new ArrayList<>();
		JsonNode candidates = node.path("candidates");

		if (candidates.isArray()) {
			for (JsonNode candidate : candidates) {
				JsonNode parts = candidate.path("content").path("parts");
				if (parts.isArray()) {
					for (JsonNode part : parts) {
						if (part.path("thought").asBoolean(false)) {
							String thoughtText = part.path("text").asString("");
							if (!thoughtText.isEmpty()) {
								outputLines.add(OpenAiSseLine.reasoningDelta(
										objectMapper,
										chunkId,
										created,
										model(),
										thoughtText
								));
							}
						} else if (part.has("text")) {
							String text = part.path("text").asString("");
							if (!text.isEmpty()) {
								outputLines.add(OpenAiSseLine.delta(objectMapper, chunkId, created, model(), text));
							}
						} else if (part.has("functionCall")) {
							hasToolCalls = true;
							JsonNode funcCall = part.path("functionCall");
							String funcName = funcCall.path("name").asString("");
							JsonNode argsNode = funcCall.path("args");
							String argsJson = serializeArgs(argsNode);
							String toolId = UniversalToolNormalizer.generateSyntheticToolCallId(
									funcName,
									toolCallIndex++
							);
							outputLines.add(OpenAiSseLine.toolCallHeader(
									objectMapper, chunkId, created, model(),
									0, toolId, funcName, argsJson
							));
						}
					}
				}

				if (candidate.has("finishReason") && !candidate.get("finishReason").isNull()) {
					String rawFinishReason = candidate.get("finishReason").asString("");
					if (!rawFinishReason.isBlank()) {
						done = true;
						String finishReason = mapFinishReason(rawFinishReason);
						if (includeUsageInResponse) {
							UsageInfo u = usage();
							if (u != null) {
								outputLines.add(OpenAiSseLine.usageWithDetails(
										objectMapper, chunkId, created, model(),
										u.promptTokens(), u.completionTokens(),
										cachedTokens != null ? cachedTokens : 0L,
										reasoningTokens != null ? reasoningTokens : 0L
								));
							}
						}
						outputLines.add(OpenAiSseLine.finishedWithReason(
								objectMapper,
								chunkId,
								created,
								model(),
								finishReason
						));
						outputLines.add(OpenAiSseLine.DONE);
						break;
					}
				}
			}
		}

		return outputLines;
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public @Nullable UsageInfo usage() {
		if (inputTokens == null && outputTokens == null) {
			return null;
		}
		return new UsageInfo(
				inputTokens != null ? inputTokens : 0L,
				outputTokens != null ? outputTokens : 0L
		);
	}

	@Override
	public @Nullable String upstreamModel() {
		return model();
	}

	private String mapFinishReason(String geminiFinishReason) {
		return switch (geminiFinishReason.toUpperCase()) {
			case "STOP" -> hasToolCalls ? "tool_calls" : "stop";
			case "MAX_TOKENS" -> "length";
			case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "content_filter";
			case "MALFORMED_FUNCTION_CALL" -> "tool_calls";
			default -> "stop";
		};
	}

	private String serializeArgs(JsonNode argsNode) {
		if (argsNode == null || argsNode.isNull() || argsNode.isMissingNode()) {
			return "{}";
		}
		try {
			return objectMapper.writeValueAsString(argsNode);
		} catch (Exception ex) {
			return "{}";
		}
	}

	private String model() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	private @Nullable JsonNode parse(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.debug("Ignoring unparseable Gemini data line: {}", json);
			return null;
		}
	}
}
