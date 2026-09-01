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

@DisplayName("OpenAiEmbeddingAdapter")
@SuppressWarnings("DataFlowIssue")
class OpenAiEmbeddingAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OpenAiEmbeddingAdapter adapter = new OpenAiEmbeddingAdapter(objectMapper);

	@Test
	@DisplayName("adapter properties are correctly configured")
	void adapterProperties() {
		assertThat(adapter.getProviderType()).isEqualTo(ProviderType.OPENAI);
		assertThat(adapter.getMaxBatchSize()).isEqualTo(2048);
	}

	@Test
	@DisplayName("buildRequest serializes inputs, dimensions, encoding_format, user, and authorization header")
	void buildRequestSerialization() {
		EmbeddingRequest request = new EmbeddingRequest(
				List.of("hello", "world"),
				"text-embedding-3-small",
				512,
				"base64",
				"user-123"
		);
		ProviderConfig providerConfig = new ProviderConfig(
				"openai", ProviderType.OPENAI, URI.create("https://api.openai.com"),
				new SensitiveString("test-api-key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);

		HttpRequest httpRequest = adapter.buildRequest(
				request, List.of("hello", "world"), providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);

		assertThat(httpRequest.uri()).isEqualTo(URI.create("https://api.openai.com/v1/embeddings"));
		assertThat(httpRequest.headers().firstValue("Authorization")).contains("Bearer test-api-key");
		assertThat(httpRequest.headers().firstValue("Content-Type")).contains("application/json");
	}

	@Test
	@DisplayName("buildRequest omits Authorization header when API key is blank or null")
	void buildRequestWithoutApiKey() {
		EmbeddingRequest request = new EmbeddingRequest("text", "model", null, null, null);
		ProviderConfig providerConfig = new ProviderConfig(
				"local", ProviderType.OPENAI, URI.create("http://localhost:8000"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(30)
		);

		HttpRequest httpRequest = adapter.buildRequest(
				request, List.of("text"), providerConfig, URI.create("http://localhost:8000/v1/embeddings")
		);

		assertThat(httpRequest.headers().firstValue("Authorization")).isEmpty();

		ProviderConfig blankKeyConfig = new ProviderConfig(
				"local", ProviderType.OPENAI, URI.create("http://localhost:8000"),
				new SensitiveString("   "), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		HttpRequest blankReq = adapter.buildRequest(
				request, List.of("text"), blankKeyConfig, URI.create("http://localhost:8000/v1/embeddings")
		);
		assertThat(blankReq.headers().firstValue("Authorization")).isEmpty();
	}

	@Test
	@DisplayName("parseResponse handles response without data array safely")
	void parseResponseNoData() {
		String json = """
				{
				  "object": "list",
				  "model": "text-embedding-3-small"
				}
				""";
		EmbeddingRequest request = new EmbeddingRequest("text", "model", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8),
				request,
				"model"
		);
		assertThat(result.vectors()).isEmpty();
		assertThat(result.promptTokens()).isZero();
	}

	@Test
	@DisplayName("parseResponse extracts float vectors and prompt tokens")
	void parseResponseFloats() {
		String json = """
				{
				  "object": "list",
				  "data": [
				    {
				      "object": "embedding",
				      "index": 0,
				      "embedding": [0.1, 0.2, 0.3]
				    }
				  ],
				  "model": "text-embedding-3-small",
				  "usage": {
				    "prompt_tokens": 8,
				    "total_tokens": 8
				  }
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "text-embedding-3-small", null, "float", null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "text-embedding-3-small"
		);

		assertThat(result.promptTokens()).isEqualTo(8);
		assertThat(result.vectors()).hasSize(1);
		assertThat(result.vectors().getFirst()).containsExactly(0.1f, 0.2f, 0.3f);
		assertThat(result.base64Vectors()).isNull();
	}

	@Test
	@DisplayName("parseResponse extracts Base64 vector strings and decodes floats")
	void parseResponseBase64() {
		float[] vec = new float[]{0.5f, -0.5f};
		String b64 = VectorEncodingUtils.encodeToBase64(vec);

		String json = """
				{
				  "object": "list",
				  "data": [
				    {
				      "object": "embedding",
				      "index": 0,
				      "embedding": "%s"
				    }
				  ],
				  "model": "text-embedding-3-small",
				  "usage": {
				    "prompt_tokens": 5,
				    "total_tokens": 5
				  }
				}
				""".formatted(b64);

		EmbeddingRequest request = new EmbeddingRequest("text", "text-embedding-3-small", null, "base64", null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "text-embedding-3-small"
		);

		assertThat(result.promptTokens()).isEqualTo(5);
		assertThat(result.base64Vectors()).containsExactly(b64);
		assertThat(result.vectors().getFirst()).containsExactly(vec);
	}

	@Test
	@DisplayName("parseResponse handles unexpected node types gracefully")
	void parseResponseUnexpectedNode() {
		String json = """
				{
				  "object": "list",
				  "data": [
				    {
				      "object": "embedding",
				      "index": 0,
				      "embedding": 12345
				    }
				  ],
				  "model": "text-embedding-3-small"
				}
				""";
		EmbeddingRequest request = new EmbeddingRequest("text", "model", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8),
				request,
				"model"
		);
		assertThat(result.vectors()).isEmpty();
	}

	@Test
	@DisplayName("parseResponse throws IllegalArgumentException on malformed json")
	void parseResponseMalformed() {
		EmbeddingRequest request = new EmbeddingRequest("text", "model", null, null, null);
		assertThatThrownBy(() -> adapter.parseResponse("not json".getBytes(StandardCharsets.UTF_8), request, "model"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Failed to parse OpenAI embeddings response");
	}
}
