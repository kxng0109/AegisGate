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
 * Protocol adapter for Cohere embeddings API ({@code v2/embed} and {@code v1/embed}).
 */
@Component
@RequiredArgsConstructor
public class CohereEmbeddingAdapter implements EmbeddingAdapter {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final int MAX_BATCH_SIZE = 96;

	private final ObjectMapper objectMapper;

	@Override
	public ProviderType getProviderType() {
		return ProviderType.ANTHROPIC; // Or custom/cohere mapped provider
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
		ArrayNode textsArray = root.putArray("texts");
		for (String text : textBatch) {
			textsArray.add(text);
		}
		root.put("model", request.model());
		root.put("input_type", "search_document");
		root.put("truncate", "END");

		ArrayNode typesArray = root.putArray("embedding_types");
		if (request.isBase64Requested()) {
			typesArray.add("base64");
		} else {
			typesArray.add("float");
		}

		if (request.dimensions() != null) {
			root.put("output_dimension", request.dimensions());
		}

		byte[] bodyBytes = objectMapper.writeValueAsBytes(root);

		HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
		                                         .timeout(TIMEOUT)
		                                         .header("Content-Type", "application/json")
		                                         .header("X-Client-Name", "AegisGate")
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
			int promptTokens = root.path("meta").path("tokens").path("input_tokens").asInt(
					root.path("meta").path("billed_units").path("input_tokens").asInt(0)
			);

			List<float[]> floatVectors = new ArrayList<>();
			List<String> base64Vectors = new ArrayList<>();
			boolean isBase64Response = false;

			JsonNode embeddingsNode = root.path("embeddings");
			JsonNode b64Node = embeddingsNode.path("base64");
			JsonNode floatNode = embeddingsNode.path("float");

			if (b64Node.isArray()) {
				isBase64Response = true;
				for (JsonNode b64Item : b64Node) {
					String b64 = b64Item.asText();
					base64Vectors.add(b64);
					floatVectors.add(VectorEncodingUtils.decodeFromBase64(b64));
				}
			} else {
				JsonNode targetNode = floatNode.isArray() ? floatNode : embeddingsNode;
				if (targetNode.isArray()) {
					for (JsonNode vecNode : targetNode) {
						if (vecNode.isArray()) {
							float[] vec = new float[vecNode.size()];
							for (int i = 0; i < vecNode.size(); i++) {
								vec[i] = (float) vecNode.get(i).asDouble();
							}
							floatVectors.add(vec);
						}
					}
				}
			}

			if (isBase64Response) {
				return NormalizedEmbeddingResult.ofBase64(base64Vectors, floatVectors, promptTokens);
			}
			return NormalizedEmbeddingResult.ofFloats(floatVectors, promptTokens);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Failed to parse Cohere embeddings response: " + ex.getMessage(), ex);
		}
	}
}
