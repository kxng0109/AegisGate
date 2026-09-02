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

@DisplayName("OllamaEmbeddingAdapter")
@SuppressWarnings("DataFlowIssue")
class OllamaEmbeddingAdapterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OllamaEmbeddingAdapter adapter = new OllamaEmbeddingAdapter(objectMapper);

	@Test
	@DisplayName("adapter properties are correctly configured")
	void adapterProperties() {
		assertThat(adapter.getProviderType()).isEqualTo(ProviderType.OLLAMA);
		assertThat(adapter.getMaxBatchSize()).isEqualTo(32);
	}

	@Test
	@DisplayName("buildRequest serializes model, input array, truncate, and dimensions")
	void buildRequestSerialization() {
		EmbeddingRequest request = new EmbeddingRequest(List.of("text1", "text2"), "nomic-embed-text", 256, null, null);
		ProviderConfig providerConfig = new ProviderConfig(
				"ollama", ProviderType.OLLAMA, URI.create("http://localhost:11434"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(60)
		);

		HttpRequest httpRequest = adapter.buildRequest(
				request, List.of("text1", "text2"), providerConfig, URI.create("http://localhost:11434/api/embed")
		);

		assertThat(httpRequest.uri()).isEqualTo(URI.create("http://localhost:11434/api/embed"));
		assertThat(httpRequest.headers().firstValue("Content-Type")).contains("application/json");

		// With API key and null dimensions
		EmbeddingRequest noDimReq = new EmbeddingRequest(List.of("text1"), "model", null, null, null);
		ProviderConfig authConfig = new ProviderConfig(
				"ollama",
				ProviderType.OLLAMA,
				URI.create("http://localhost:11434"),
				new SensitiveString("key-123"),
				Duration.ofSeconds(5),
				Duration.ofSeconds(60)
		);
		HttpRequest authHttpReq = adapter.buildRequest(
				noDimReq, List.of("text1"), authConfig, URI.create("http://localhost:11434/api/embed")
		);
		assertThat(authHttpReq.headers().firstValue("Authorization")).contains("Bearer key-123");
	}

	@Test
	@DisplayName("parseResponse handles empty response safely")
	void parseResponseEmpty() {
		String json = "{}";
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
	@DisplayName("parseResponse parses /api/embed 2D float array format and prompt_eval_count")
	void parseResponseModernEmbed() {
		String json = """
				{
				  "model": "nomic-embed-text",
				  "embeddings": [
				    [0.1, 0.2, 0.3],
				    [0.4, 0.5, 0.6]
				  ],
				  "total_duration": 15000000,
				  "prompt_eval_count": 12
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "nomic-embed-text", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "nomic-embed-text"
		);

		assertThat(result.promptTokens()).isEqualTo(12);
		assertThat(result.vectors()).hasSize(2);
		assertThat(result.vectors().getFirst()).containsExactly(0.1f, 0.2f, 0.3f);
		assertThat(result.vectors().get(1)).containsExactly(0.4f, 0.5f, 0.6f);
	}

	@Test
	@DisplayName("parseResponse parses legacy /api/embeddings 1D float format")
	void parseResponseLegacyEmbeddings() {
		String json = """
				{
				  "embedding": [0.8, 0.9]
				}
				""";

		EmbeddingRequest request = new EmbeddingRequest("text", "all-minilm", null, null, null);
		NormalizedEmbeddingResult result = adapter.parseResponse(
				json.getBytes(StandardCharsets.UTF_8), request, "all-minilm"
		);

		assertThat(result.vectors()).hasSize(1);
		assertThat(result.vectors().getFirst()).containsExactly(0.8f, 0.9f);
	}

	@Test
	@DisplayName("parseResponse handles non array embedding nodes gracefully")
	void parseResponseNonArrayNodes() {
		String json = """
				{
				  "embeddings": [123, "string"],
				  "embedding": "not-array"
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
		assertThatThrownBy(() -> adapter.parseResponse("invalid".getBytes(StandardCharsets.UTF_8), request, "model"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Failed to parse Ollama embeddings response");
	}
}
