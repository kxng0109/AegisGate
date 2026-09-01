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
 * Protocol adapter for OpenAI and OpenAI-compatible embedding endpoints.
 */
@Component
@RequiredArgsConstructor
public class OpenAiEmbeddingAdapter implements EmbeddingAdapter {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final int MAX_BATCH_SIZE = 2048;

	private final ObjectMapper objectMapper;

	@Override
	public ProviderType getProviderType() {
		return ProviderType.OPENAI;
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
		ArrayNode inputArray = root.putArray("input");
		for (String text : textBatch) {
			inputArray.add(text);
		}
		root.put("model", request.model());
		if (request.dimensions() != null) {
			root.put("dimensions", request.dimensions());
		}
		if (request.encodingFormat() != null) {
			root.put("encoding_format", request.encodingFormat());
		}
		if (request.user() != null) {
			root.put("user", request.user());
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
			JsonNode dataNode = root.path("data");
			int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);

			List<float[]> floatVectors = new ArrayList<>();
			List<String> base64Vectors = new ArrayList<>();
			boolean isBase64Response = false;

			if (dataNode.isArray()) {
				for (JsonNode item : dataNode) {
					JsonNode embNode = item.path("embedding");
					if (embNode.isString()) {
						isBase64Response = true;
						String b64 = embNode.asString();
						base64Vectors.add(b64);
						floatVectors.add(VectorEncodingUtils.decodeFromBase64(b64));
					} else if (embNode.isArray()) {
						float[] vec = new float[embNode.size()];
						for (int i = 0; i < embNode.size(); i++) {
							vec[i] = (float) embNode.get(i).asDouble();
						}
						floatVectors.add(vec);
					}
				}
			}

			if (isBase64Response) {
				return NormalizedEmbeddingResult.ofBase64(base64Vectors, floatVectors, promptTokens);
			}
			return NormalizedEmbeddingResult.ofFloats(floatVectors, promptTokens);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Failed to parse OpenAI embeddings response: " + ex.getMessage(), ex);
		}
	}
}
