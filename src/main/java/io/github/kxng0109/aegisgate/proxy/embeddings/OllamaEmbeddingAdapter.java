package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Protocol adapter for Ollama embeddings API ({@code /api/embed} and {@code /api/embeddings}).
 */
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingAdapter implements EmbeddingAdapter {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);
	private static final int MAX_BATCH_SIZE = 32;

	private final ObjectMapper objectMapper;

	@Override
	public ProviderType getProviderType() {
		return ProviderType.OLLAMA;
	}

	@Override
	public int getMaxBatchSize() {
		return MAX_BATCH_SIZE;
	}

	@Override
	public HttpRequest buildRequest(
			EmbeddingRequest request,
			List<String> textBatch,
			ProviderConfig providerConfig,
			URI targetUri
	) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", request.model());

		ArrayNode inputArray = root.putArray("input");
		for (String text : textBatch) {
			inputArray.add(text);
		}
		root.put("truncate", true);

		if (request.dimensions() != null) {
			root.put("dimensions", request.dimensions());
		}

		byte[] bodyBytes = objectMapper.writeValueAsBytes(root);

		HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
		                                         .timeout(TIMEOUT)
		                                         .header("Content-Type", "application/json")
		                                         .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));

		String apiKey = providerConfig.apiKey() != null ? providerConfig.apiKey().value() : "";
		if (!apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}

		return builder.build();
	}

	@Override
	public NormalizedEmbeddingResult parseResponse(
			byte[] responseBody,
			EmbeddingRequest originalRequest,
			String modelName
	) {
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			int promptTokens = root.path("prompt_eval_count").asInt(0);

			List<float[]> floatVectors = new ArrayList<>();
			JsonNode embeddingsNode = root.path("embeddings");
			JsonNode singleEmbNode = root.path("embedding");

			if (embeddingsNode.isArray()) {
				for (JsonNode vecNode : embeddingsNode) {
					if (vecNode.isArray()) {
						float[] vec = new float[vecNode.size()];
						for (int i = 0; i < vecNode.size(); i++) {
							vec[i] = (float) vecNode.get(i).asDouble();
						}
						floatVectors.add(vec);
					}
				}
			} else if (singleEmbNode.isArray()) {
				// Legacy /api/embeddings format
				float[] vec = new float[singleEmbNode.size()];
				for (int i = 0; i < singleEmbNode.size(); i++) {
					vec[i] = (float) singleEmbNode.get(i).asDouble();
				}
				floatVectors.add(vec);
			}

			return NormalizedEmbeddingResult.ofFloats(floatVectors, promptTokens);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Failed to parse Ollama embeddings response: " + ex.getMessage(), ex);
		}
	}
}
