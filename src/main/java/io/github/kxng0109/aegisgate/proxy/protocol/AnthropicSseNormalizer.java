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
 * Rewrites the Anthropic Messages streaming event sequence into OpenAI shaped chunks.
 *
 * <p>Anthropic streams typed SSE events across two lines, an {@code event:}
 * line naming the event and a {@code data:} line carrying its JSON. The mapping follows the documented event flow:</p>
 * <ul>
 *   <li>{@code message_start} contributes the input token count and the model
 *       id;</li>
 *   <li>{@code content_block_delta} with a {@code text_delta} becomes a
 *       content chunk, while thinking and tool input deltas are dropped;</li>
 *   <li>{@code message_delta} contributes the cumulative output token
 *       count;</li>
 *   <li>{@code message_stop} emits the final chunk and the {@code [DONE]}
 *       marker;</li>
 *   <li>{@code ping} and unknown event types are ignored, because the API may
 *       add new event types at any time.</li>
 * </ul>
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
	private @Nullable String upstreamModel;
	private boolean done;

	/**
	 * @param objectMapper           parses each data line
	 * @param fallbackModel          model to attribute cost against when the provider never reports one
	 * @param includeUsageInResponse whether the usage chunk is relayed to the client (the client asked for it)
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

	private List<String> messageStart(JsonNode node) {
		JsonNode message = node.get("message");
		if (message != null && message.isObject()) {
			JsonNode modelNode = message.get("model");
			if (modelNode != null && modelNode.isString() && !modelNode.asString().isEmpty()) {
				upstreamModel = modelNode.asString();
			}
			long tokens = message.path("usage").path("input_tokens").asLong(0);
			if (tokens > 0 || inputTokens == null) {
				inputTokens = tokens;
			}
		}
		return List.of();
	}

	private List<String> contentDelta(JsonNode node) {
		String type = node.path("delta").path("type").asString("");
		if (!"text_delta".equals(type)) {
			return List.of();
		}
		String text = node.path("delta").path("text").asString("");
		if (text.isEmpty()) {
			return List.of();
		}
		return List.of(OpenAiSseLine.delta(objectMapper, chunkId, created, model(), text));
	}

	private List<String> messageDelta(JsonNode node) {
		JsonNode usageNode = node.get("usage");
		if (usageNode != null && usageNode.isObject() && usageNode.has("output_tokens")) {
			outputTokens = usageNode.get("output_tokens").asLong();
		}
		return List.of();
	}

	private List<String> messageStop() {
		done = true;
		List<String> lines = new ArrayList<>();
		if (includeUsageInResponse) {
			UsageInfo usageInfo = usage();
			if (usageInfo != null) {
				lines.add(OpenAiSseLine.usage(
						objectMapper, chunkId, created, model(),
						usageInfo.promptTokens(), usageInfo.completionTokens()
				));
			}
		}
		lines.add(OpenAiSseLine.finished(objectMapper, chunkId, created, model()));
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