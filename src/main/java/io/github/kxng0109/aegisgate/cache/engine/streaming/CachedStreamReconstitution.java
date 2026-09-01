package io.github.kxng0109.aegisgate.cache.engine.streaming;

import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Reconstitutes cached completions into OpenAI-compliant Server-Sent Events (SSE) streams.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachedStreamReconstitution {

	private final ObjectMapper objectMapper;

	/**
	 * Streams a cached completion to the downstream client as valid OpenAI SSE chunks.
	 *
	 * @param entry          cached completion entry
	 * @param requestedModel client-facing requested model alias
	 * @param requestsUsage  whether the client asked for usage metadata in stream_options
	 * @param out            downstream servlet output stream
	 * @throws IOException if writing to client fails
	 */
	public void streamCachedResponse(
			CacheEntry entry,
			String requestedModel,
			boolean requestsUsage,
			OutputStream out
	) throws IOException {
		String streamId = "chatcmpl-cache-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		long created = Instant.now().getEpochSecond();
		String contentText = extractCompletionContent(entry.responsePayloadJson());

		// 1. Chunk 0: Role Initialization
		writeSseLine(out, "data: " + buildRoleChunkJson(streamId, created, requestedModel));

		// 2. Chunks 1..N: Content Deltas (sliced in chunks of ~32 characters)
		int chunkSize = 32;
		for (int i = 0; i < contentText.length(); i += chunkSize) {
			int end = Math.min(contentText.length(), i + chunkSize);
			String slice = contentText.substring(i, end);
			writeSseLine(out, "data: " + buildContentDeltaChunkJson(streamId, created, requestedModel, slice));
		}

		// 3. Finish Reason Chunk
		writeSseLine(out, "data: " + buildFinishChunkJson(streamId, created, requestedModel));

		// 4. Usage Chunk (if requested)
		if (requestsUsage) {
			writeSseLine(out, "data: " + buildUsageChunkJson(streamId, created, requestedModel, entry));
		}

		// 5. Termination Delimiter
		writeSseLine(out, "data: [DONE]");
		out.flush();
	}

	/**
	 * Extracts completion text from cached JSON payload or returns the raw string if not JSON.
	 *
	 * @param payloadJson cached JSON payload
	 * @return plain completion text
	 */
	public String extractCompletionContent(String payloadJson) {
		if (payloadJson == null || payloadJson.isBlank()) {
			return "";
		}
		try {
			JsonNode root = objectMapper.readTree(payloadJson);
			if (root.has("choices") && root.get("choices").isArray() && !root.get("choices").isEmpty()) {
				JsonNode choice = root.get("choices").get(0);
				if (choice.has("message") && choice.get("message").has("content")) {
					return choice.get("message").get("content").asText("");
				}
				if (choice.has("delta") && choice.get("delta").has("content")) {
					return choice.get("delta").get("content").asText("");
				}
				if (choice.has("text")) {
					return choice.get("text").asText("");
				}
			}
			if (root.has("content")) {
				return root.get("content").asText("");
			}
		} catch (Exception ignored) {
		}
		return payloadJson;
	}

	private void writeSseLine(OutputStream out, String line) throws IOException {
		out.write(line.getBytes(StandardCharsets.UTF_8));
		out.write('\n');
		out.write('\n');
	}

	private String buildRoleChunkJson(String id, long created, String model) {
		return "{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
				+ ",\"model\":\"" + model
				+ "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}";
	}

	private String buildContentDeltaChunkJson(String id, long created, String model, String slice) {
		try {
			String escapedSlice = objectMapper.writeValueAsString(slice);
			return "{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
					+ ",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":" + escapedSlice
					+ "},\"finish_reason\":null}]}";
		} catch (Exception ex) {
			return "{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
					+ ",\"model\":\"" + model
					+ "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":null}]}";
		}
	}

	private String buildFinishChunkJson(String id, long created, String model) {
		return "{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
				+ ",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
	}

	private String buildUsageChunkJson(String id, long created, String model, CacheEntry entry) {
		return "{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
				+ ",\"model\":\"" + model + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":"
				+ entry.promptTokens() + ",\"completion_tokens\":" + entry.completionTokens()
				+ ",\"total_tokens\":" + entry.totalTokens() + "}}";
	}
}
