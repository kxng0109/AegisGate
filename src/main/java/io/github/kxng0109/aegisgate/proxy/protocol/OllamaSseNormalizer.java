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
 * Rewrites the Ollama chat stream into OpenAI shaped chunks.
 *
 * <p>Ollama's {@code /api/chat} streams newline delimited JSON rather than
 * SSE. Every line carries the assistant's next content fragment, and the final line carries {@code done: true} together
 * with the token counts ({@code prompt_eval_count} for the prompt, {@code eval_count} for the completion). Content
 * fragments become content chunks; the done line emits the final chunk, the optional usage chunk, and the
 * {@code [DONE]} marker.</p>
 */
@Slf4j
public final class OllamaSseNormalizer implements SseNormalizer {

	private final ObjectMapper objectMapper;
	private final String chunkId;
	private final long created;
	private final String fallbackModel;
	private final boolean includeUsageInResponse;

	private @Nullable UsageInfo usage;
	private @Nullable String upstreamModel;
	private boolean done;

	/**
	 * @param objectMapper           parses each newline delimited JSON line
	 * @param fallbackModel          model to attribute cost against when the provider never reports one
	 * @param includeUsageInResponse whether the usage chunk is relayed to the client (the client asked for it)
	 */
	public OllamaSseNormalizer(ObjectMapper objectMapper, String fallbackModel, boolean includeUsageInResponse) {
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
		JsonNode node = parse(trimmed);
		if (node == null || !node.isObject()) {
			return List.of();
		}

		String model = node.path("model").asText("");
		if (!model.isEmpty()) {
			upstreamModel = model;
		}

		String content = node.path("message").path("content").asText("");
		boolean finished = node.path("done").asBoolean(false);

		List<String> lines = new ArrayList<>();
		if (!content.isEmpty()) {
			lines.add(OpenAiSseLine.delta(objectMapper, chunkId, created, model(), content));
		}
		if (finished) {
			done = true;
			usage = new UsageInfo(
					node.path("prompt_eval_count").asLong(0),
					node.path("eval_count").asLong(0)
			);
			if (includeUsageInResponse) {
				lines.add(OpenAiSseLine.usage(
						objectMapper, chunkId, created, model(),
						usage.promptTokens(), usage.completionTokens()
				));
			}
			lines.add(OpenAiSseLine.finished(objectMapper, chunkId, created, model()));
			lines.add(OpenAiSseLine.DONE);
		}
		return lines;
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

	private String model() {
		return upstreamModel != null ? upstreamModel : fallbackModel;
	}

	private @Nullable JsonNode parse(String line) {
		try {
			return objectMapper.readTree(line);
		} catch (JacksonException ex) {
			log.debug("Ignoring an unparseable Ollama stream line");
			return null;
		}
	}
}