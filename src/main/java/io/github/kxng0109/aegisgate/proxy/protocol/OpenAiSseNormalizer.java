package io.github.kxng0109.aegisgate.proxy.protocol;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Pass-through normalizer for OpenAI compatible providers.
 *
 * <p>The upstream already speaks the client contract, so every line is relayed
 * untouched. The only exception is the usage chunk: the gateway always asks the upstream for usage (see
 * {@link OpenAiPassthroughAdapter}) so it can bill, but the usage chunk is only forwarded to the client when the client
 * requested {@code stream_options.include_usage}. When the client did not ask for it, the chunk is consumed for
 * accounting and then dropped.</p>
 */
@Slf4j
public final class OpenAiSseNormalizer implements SseNormalizer {

	private final ObjectMapper objectMapper;
	private final String fallbackModel;
	private final boolean includeUsageInResponse;

	private @Nullable UsageInfo usage;
	private @Nullable String upstreamModel;
	private boolean done;

	/**
	 * @param objectMapper           parses each data line
	 * @param fallbackModel          model to attribute cost against when the provider never reports one
	 * @param includeUsageInResponse whether the usage chunk is relayed to the client (the client asked for it)
	 */
	public OpenAiSseNormalizer(ObjectMapper objectMapper, String fallbackModel, boolean includeUsageInResponse) {
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
		if (trimmed.isEmpty()) {
			return List.of();
		}
		if (OpenAiSseLine.DONE.equals(trimmed)) {
			done = true;
			return List.of(OpenAiSseLine.DONE);
		}
		if (trimmed.startsWith("data:")) {
			String json = trimmed.substring("data:".length()).trim();
			JsonNode node = parse(json);
			if (node != null && node.isObject()) {
				String model = node.path("model").asString("");
				if (!model.isEmpty()) {
					upstreamModel = model;
				}
				JsonNode usageNode = node.get("usage");
				JsonNode choices = node.get("choices");
				if (usageNode != null && usageNode.isObject()
						&& choices != null && choices.isArray() && choices.isEmpty()) {
					usage = new UsageInfo(
							usageNode.path("prompt_tokens").asLong(0),
							usageNode.path("completion_tokens").asLong(0)
					);
					if (includeUsageInResponse) {
						return List.of(rawLine);
					}
					return List.of();
				}
			}
		}
		return List.of(rawLine);
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public @Nullable UsageInfo usage() {
		return usage;
	}

	@Override
	public @Nullable String upstreamModel() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	private @Nullable JsonNode parse(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.debug("Ignoring an unparseable OpenAI data line");
			return null;
		}
	}
}