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
 * Rewrites the Anthropic Messages streaming event sequence into OpenAI shaped chunks,
 * supporting text content, tool calling ({@code tool_use}), thinking deltas, and prompt caching telemetry.
 */
@Slf4j
public final class AnthropicSseNormalizer implements SseNormalizer {

	private static final String EVENT_PREFIX = "event:";
	private static final String DATA_PREFIX = "data:";

	private final ObjectMapper objectMapper;
	private final String chunkId;
	private final long created;
	private final String fallbackModel;
	private final boolean includeUsageInResponse;

	private String pendingEvent = "";
	private @Nullable Long inputTokens;
	private @Nullable Long outputTokens;
	private @Nullable Long cacheCreationInputTokens;
	private @Nullable Long cacheReadInputTokens;
	private @Nullable Long reasoningTokens;
	private @Nullable String upstreamModel;
	private String finishReason = "stop";
	private int activeToolIndex;
	private boolean done;

	/**
	 * @param objectMapper           parses each data line
	 * @param fallbackModel          model to attribute cost against when the provider never reports one
	 * @param includeUsageInResponse whether the usage chunk is relayed to the client
	 */
	public AnthropicSseNormalizer(ObjectMapper objectMapper, String fallbackModel, boolean includeUsageInResponse) {
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
		if (trimmed.isEmpty()) {
			return List.of();
		}
		if (trimmed.startsWith(EVENT_PREFIX)) {
			pendingEvent = trimmed.substring(EVENT_PREFIX.length()).trim();
			return List.of();
		}
		if (!trimmed.startsWith(DATA_PREFIX)) {
			return List.of();
		}
		String json = trimmed.substring(DATA_PREFIX.length()).trim();
		JsonNode node = parse(json);
		if (node == null || !node.isObject()) {
			return List.of();
		}
		String type = node.path("type").asString("");
		if (!type.isEmpty()) {
			pendingEvent = type;
		}
		return switch (pendingEvent) {
			case "message_start" -> messageStart(node);
			case "content_block_start" -> contentBlockStart(node);
			case "content_block_delta" -> contentDelta(node);
			case "message_delta" -> messageDelta(node);
			case "message_stop" -> messageStop();
			default -> List.of();
		};
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public @Nullable UsageInfo usage() {
		if (!done || inputTokens == null && outputTokens == null) {
			return null;
		}
		return new UsageInfo(
				inputTokens == null ? 0 : inputTokens,
				outputTokens == null ? 0 : outputTokens
		);
	}

	@Override
	public @Nullable String upstreamModel() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	/**
	 * Returns prompt cache write tokens observed from Anthropic usage.
	 *
	 * @return write token count or 0 if not reported
	 */
	public long cacheCreationInputTokens() {
		return cacheCreationInputTokens != null ? cacheCreationInputTokens : 0L;
	}

	/**
	 * Returns prompt cache read tokens observed from Anthropic usage.
	 *
	 * @return read token count or 0 if not reported
	 */
	public long cacheReadInputTokens() {
		return cacheReadInputTokens != null ? cacheReadInputTokens : 0L;
	}

	/**
	 * Returns reasoning / thinking tokens observed from Anthropic usage.
	 *
	 * @return reasoning token count or 0 if not reported
	 */
	public long reasoningTokens() {
		return reasoningTokens != null ? reasoningTokens : 0L;
	}

	private List<String> messageStart(JsonNode node) {
		JsonNode message = node.get("message");
		if (message != null && message.isObject()) {
			JsonNode modelNode = message.get("model");
			if (modelNode != null && modelNode.isString() && !modelNode.asString().isEmpty()) {
				upstreamModel = modelNode.asString();
			}
			JsonNode usageNode = message.path("usage");
			long tokens = usageNode.path("input_tokens").asLong(0);
			if (tokens > 0 || inputTokens == null) {
				inputTokens = tokens;
			}
			if (usageNode.has("cache_creation_input_tokens")) {
				cacheCreationInputTokens = usageNode.path("cache_creation_input_tokens").asLong(0);
			}
			if (usageNode.has("cache_read_input_tokens")) {
				cacheReadInputTokens = usageNode.path("cache_read_input_tokens").asLong(0);
			}
		}
		return List.of();
	}

	private List<String> contentBlockStart(JsonNode node) {
		JsonNode block = node.path("content_block");
		String blockType = block.path("type").asString("");
		if ("tool_use".equals(blockType)) {
			String toolId = block.path("id").asString("");
			String name = block.path("name").asString("");
			int index = node.path("index").asInt(activeToolIndex);
			activeToolIndex = index;
			return List.of(OpenAiSseLine.toolCallHeader(
					objectMapper,
					chunkId,
					created,
					model(),
					index,
					toolId,
					name,
					""
			));
		}
		return List.of();
	}

	private List<String> contentDelta(JsonNode node) {
		JsonNode deltaNode = node.path("delta");
		String type = deltaNode.path("type").asString("");
		if ("text_delta".equals(type)) {
			String text = deltaNode.path("text").asString("");
			if (!text.isEmpty()) {
				return List.of(OpenAiSseLine.delta(objectMapper, chunkId, created, model(), text));
			}
		} else if ("input_json_delta".equals(type)) {
			String partialJson = deltaNode.path("partial_json").asString("");
			int index = node.path("index").asInt(activeToolIndex);
			return List.of(OpenAiSseLine.toolCallArgumentDelta(
					objectMapper,
					chunkId,
					created,
					model(),
					index,
					partialJson
			));
		} else if ("thinking_delta".equals(type)) {
			String thinkingText = deltaNode.path("thinking").asString("");
			if (!thinkingText.isEmpty()) {
				return List.of(OpenAiSseLine.reasoningDelta(objectMapper, chunkId, created, model(), thinkingText));
			}
		}
		return List.of();
	}

	private List<String> messageDelta(JsonNode node) {
		JsonNode delta = node.get("delta");
		if (delta != null && delta.isObject() && delta.has("stop_reason")) {
			String stopReason = delta.get("stop_reason").asString("");
			if ("tool_use".equals(stopReason)) {
				finishReason = "tool_calls";
			} else if ("max_tokens".equals(stopReason)) {
				finishReason = "length";
			} else if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) {
				finishReason = "stop";
			}
		}
		JsonNode usageNode = node.get("usage");
		if (usageNode != null && usageNode.isObject()) {
			if (usageNode.has("output_tokens")) {
				outputTokens = usageNode.get("output_tokens").asLong();
			}
			if (usageNode.has("output_tokens_details")) {
				reasoningTokens = usageNode.path("output_tokens_details").path("thinking_tokens").asLong(0);
			}
		}
		return List.of();
	}

	private List<String> messageStop() {
		done = true;
		List<String> lines = new ArrayList<>();
		if (includeUsageInResponse) {
			UsageInfo usageInfo = usage();
			if (usageInfo != null) {
				lines.add(OpenAiSseLine.usageWithDetails(
						objectMapper, chunkId, created, model(),
						usageInfo.promptTokens(), usageInfo.completionTokens(),
						cacheReadInputTokens(), reasoningTokens()
				));
			}
		}
		lines.add(OpenAiSseLine.finishedWithReason(objectMapper, chunkId, created, model(), finishReason));
		lines.add(OpenAiSseLine.DONE);
		return lines;
	}

	private String model() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	private @Nullable JsonNode parse(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.debug("Ignoring an unparseable Anthropic data line");
			return null;
		}
	}
}
