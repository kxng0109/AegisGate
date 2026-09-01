package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CohereEmbeddingAdapter")
@SuppressWarnings("DataFlowIssue")
class CohereEmbeddingAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CohereEmbeddingAdapter adapter = new CohereEmbeddingAdapter(objectMapper);

	@Test
	@DisplayName("adapter properties are correctly configured")
	void adapterProperties() {
		assertThat(adapter.getMaxBatchSize()).isEqualTo(96);
	}

	@Test
	@DisplayName("buildRequest serializes texts, input_type, dimensions, base64 format, and headers")
	void buildRequestSerialization() {
		EmbeddingRequest request = new EmbeddingRequest(
				List.of("doc1", "doc2"),
				"embed-english-v3.0",
				512,
				"base64",
				null
		);
		ProviderConfig providerConfig = new ProviderConfig(
				"cohere", ProviderType.ANTHROPIC, URI.create("https://api.cohere.com"),
				new SensitiveString("cohere-key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);

		HttpRequest httpRequest = adapter.buildRequest(
				request, List.of("doc1", "doc2"), providerConfig, URI.create("https://api.cohere.com/v2/embed")
		);

		assertThat(httpRequest.uri()).isEqualTo(URI.create("https://api.cohere.com/v2/embed"));
		assertThat(httpRequest.headers().firstValue("Authorization")).contains("Bearer cohere-key");
		assertThat(httpRequest.headers().firstValue("X-Client-Name")).contains("AegisGate");

		// Without API key
		ProviderConfig noKeyConfig = new ProviderConfig(
				"cohere", ProviderType.ANTHROPIC, URI.create("https://api.cohere.com"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		HttpRequest noKeyReq = adapter.buildRequest(
				request, List.of("doc1"), noKeyConfig, URI.create("https://api.cohere.com/v2/embed")
		);
		assertThat(noKeyReq.headers().firstValue("Authorization")).isEmpty();
	}

	@Test
	@DisplayName("parseResponse parses Cohere billed_units token metadata fallback")
	void parseResponseBilledUnits() {
		String json = """
				{
				  "embeddings": [
				    [0.1, 0.2]
				  ],
				  "meta": {
				    "billed_units": {
				      "input_tokens": 42
				    }
				  }
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "embed-english-v3.0", null, "float", null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "embed-english-v3.0"
		);

		assertThat(result.promptTokens()).isEqualTo(42);
		assertThat(result.vectors()).hasSize(1);

		// Non-array vecNode in targetNode
		String nonArrayVecJson = """
				{
				  "embeddings": [
				    "not-a-vector-array"
				  ]
				}
				""";
		NormalizedEmbeddingResult nonArrayRes = adapter.parseResponse(
				nonArrayVecJson.getBytes(StandardCharsets.UTF_8), request, "embed-english-v3.0"
		);
		assertThat(nonArrayRes.vectors()).isEmpty();
	}

	@Test
	@DisplayName("parseResponse parses Cohere v2 float array format and token metadata")
	void parseResponseV2Float() {
		String json = """
				{
				  "id": "req-123",
				  "embeddings": {
				    "float": [
				      [0.1, 0.2, 0.3],
				      [0.4, 0.5, 0.6]
				    ]
				  },
				  "meta": {
				    "tokens": {
				      "input_tokens": 14
				    }
				  }
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "embed-english-v3.0", null, "float", null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "embed-english-v3.0"
		);

		assertThat(result.promptTokens()).isEqualTo(14);
		assertThat(result.vectors()).hasSize(2);
		assertThat(result.vectors().getFirst()).containsExactly(0.1f, 0.2f, 0.3f);
		assertThat(result.vectors().get(1)).containsExactly(0.4f, 0.5f, 0.6f);
	}

	@Test
	@DisplayName("parseResponse parses Cohere v2 Base64 format and billed_units token metadata")
	void parseResponseV2Base64() {
		float[] vec = new float[]{0.1f, -0.2f};
		String b64 = VectorEncodingUtils.encodeToBase64(vec);

		String json = """
				{
				  "id": "req-123",
				  "embeddings": {
				    "base64": [
				      "%s"
				    ]
				  },
				  "meta": {
				    "billed_units": {
				      "input_tokens": 7
				    }
				  }
				}
				""".formatted(b64);

		EmbeddingRequest request = new EmbeddingRequest("text", "embed-english-v3.0", null, "base64", null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "embed-english-v3.0"
		);

		assertThat(result.promptTokens()).isEqualTo(7);
		assertThat(result.base64Vectors()).containsExactly(b64);
		assertThat(result.vectors().getFirst()).containsExactly(vec);
	}

	@Test
	@DisplayName("parseResponse parses Cohere v1 direct float 2D array format")
	void parseResponseV1Direct() {
		String json = """
				{
				  "id": "req-123",
				  "embeddings": [
				    [0.7, 0.8]
				  ]
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "embed-english-v3.0", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "embed-english-v3.0"
		);

		assertThat(result.vectors()).hasSize(1);
		assertThat(result.vectors().getFirst()).containsExactly(0.7f, 0.8f);
	}

	@Test
	@DisplayName("buildRequest omits Authorization header when API key is null or blank")
	void buildRequestWithoutApiKey() {
		EmbeddingRequest request = new EmbeddingRequest(List.of("doc"), "embed-english-v3.0", null, "float", null);
		ProviderConfig providerConfig = new ProviderConfig(
				"cohere", ProviderType.ANTHROPIC, URI.create("https://api.cohere.com"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(30)
		);

		HttpRequest httpRequest = adapter.buildRequest(
				request, List.of("doc"), providerConfig, URI.create("https://api.cohere.com/v2/embed")
		);

		ProviderConfig blankKeyConfig = new ProviderConfig(
				"cohere", ProviderType.ANTHROPIC, URI.create("https://api.cohere.com"),
				new SensitiveString("   "), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		HttpRequest blankKeyHttpReq = adapter.buildRequest(
				request, List.of("doc"), blankKeyConfig, URI.create("https://api.cohere.com/v2/embed")
		);
		assertThat(blankKeyHttpReq.headers().firstValue("Authorization")).isEmpty();
	}

	@Test
	@DisplayName("parseResponse handles missing meta and empty embeddings")
	void parseResponseEmptyMeta() {
		String json = """
				{
				  "id": "req-123",
				  "embeddings": {},
				  "meta": {
				    "tokens": {}
				  }
				}
				""";
		EmbeddingRequest request = new EmbeddingRequest("text", "embed-english-v3.0", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8),
				request,
				"model"
		);
		assertThat(result.vectors()).isEmpty();
		assertThat(result.promptTokens()).isZero();

		String billedUnitsOnly = """
				{
				  "id": "req-123",
				  "embeddings": {},
				  "meta": {
				    "billed_units": {}
				  }
				}
				""";
		NormalizedEmbeddingResult res2 = adapter.parseResponse(
				billedUnitsOnly.getBytes(StandardCharsets.UTF_8),
				request,
				"model"
		);
		assertThat(res2.promptTokens()).isZero();
	}

	void parseResponseMalformed() {
		EmbeddingRequest request = new EmbeddingRequest("text", "model", null, null, null);
		assertThatThrownBy(() -> adapter.parseResponse(
				"invalid json".getBytes(StandardCharsets.UTF_8),
				request,
				"model"
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Failed to parse Cohere embeddings response");
	}
}
