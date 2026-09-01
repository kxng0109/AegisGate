package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("EmbeddingBatchOrchestrator")
@SuppressWarnings({"unchecked", "DataFlowIssue"})
class EmbeddingBatchOrchestratorTest {

	private final HttpClient httpClient = mock(HttpClient.class);
	private final EmbeddingBatchOrchestrator orchestrator = new EmbeddingBatchOrchestrator(httpClient);
	private final EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);

	private final ProviderConfig providerConfig = new ProviderConfig(
			"openai", ProviderType.OPENAI, URI.create("https://api.openai.com"),
			new SensitiveString("key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
	);

	@Test
	@DisplayName("execute returns empty response when input is empty")
	void executeEmptyInputs() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(List.of(), "text-embedding-3-small", null, null, null);
		EmbeddingResponse response = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);

		assertThat(response.data()).isEmpty();
		assertThat(response.usage().promptTokens()).isZero();
	}

	@Test
	@DisplayName("execute single batch processes and returns float vectors")
	void executeSingleBatchFloats() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(
				List.of("text1", "text2"),
				"text-embedding-3-small",
				null,
				"float",
				null
		);
		when(adapter.getMaxBatchSize()).thenReturn(100);

		HttpRequest mockHttpRequest = mock(HttpRequest.class);
		HttpResponse<byte[]> mockHttpResponse = mock(HttpResponse.class);

		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(mockHttpRequest);
		when(mockHttpResponse.statusCode()).thenReturn(200);
		when(mockHttpResponse.body()).thenReturn("{}".getBytes());
		doReturn(mockHttpResponse).when(httpClient).send(any(), any());

		float[] vec1 = new float[]{0.1f, 0.2f};
		float[] vec2 = new float[]{0.3f, 0.4f};
		when(adapter.parseResponse(any(), any(), any()))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(vec1, vec2), 10));

		EmbeddingResponse response = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);

		assertThat(response.data()).hasSize(2);
		assertThat(response.data().getFirst().index()).isZero();
		assertThat((float[]) response.data().getFirst().embedding()).containsExactly(vec1);
		assertThat(response.data().get(1).index()).isEqualTo(1);
		assertThat((float[]) response.data().get(1).embedding()).containsExactly(vec2);
		assertThat(response.usage().promptTokens()).isEqualTo(10);
	}

	@Test
	@DisplayName("execute single batch with base64 format encodes vectors or uses upstream base64")
	void executeSingleBatchBase64() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(
				List.of("text1"),
				"text-embedding-3-small",
				null,
				"base64",
				null
		);
		when(adapter.getMaxBatchSize()).thenReturn(100);

		HttpRequest mockHttpRequest = mock(HttpRequest.class);
		HttpResponse<byte[]> mockHttpResponse = mock(HttpResponse.class);

		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(mockHttpRequest);
		when(mockHttpResponse.statusCode()).thenReturn(200);
		when(mockHttpResponse.body()).thenReturn("{}".getBytes());
		doReturn(mockHttpResponse).when(httpClient).send(any(), any());

		float[] vec = new float[]{0.5f, 0.6f};
		String expectedB64 = VectorEncodingUtils.encodeToBase64(vec);
		when(adapter.parseResponse(any(), any(), any()))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(vec), 5));

		EmbeddingResponse response = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);

		assertThat(response.data()).hasSize(1);
		assertThat(response.data().getFirst().index()).isZero();
		assertThat(response.data().getFirst().embedding()).isEqualTo(expectedB64);

		// With pre-encoded base64 vectors from upstream
		when(adapter.parseResponse(any(), any(), any()))
				.thenReturn(NormalizedEmbeddingResult.ofBase64(List.of("pre-encoded-b64"), List.of(vec), 5));
		EmbeddingResponse preEncodedRes = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);
		assertThat(preEncodedRes.data().getFirst().embedding()).isEqualTo("pre-encoded-b64");
	}

	@Test
	@DisplayName("execute multi-batch partitions chunks and reassembles exact index sequence")
	void executeMultiBatchPartitioning() throws Exception {
		List<String> fiveInputs = List.of("t0", "t1", "t2", "t3", "t4");
		EmbeddingRequest request = new EmbeddingRequest(fiveInputs, "embed-english-v3.0", null, null, null);

		// Max batch size of 2 -> 3 chunks: [t0, t1], [t2, t3], [t4]
		when(adapter.getMaxBatchSize()).thenReturn(2);

		HttpRequest req0 = mock(HttpRequest.class);
		HttpRequest req1 = mock(HttpRequest.class);
		HttpRequest req2 = mock(HttpRequest.class);

		HttpResponse<byte[]> res0 = mock(HttpResponse.class);
		HttpResponse<byte[]> res1 = mock(HttpResponse.class);
		HttpResponse<byte[]> res2 = mock(HttpResponse.class);

		when(res0.statusCode()).thenReturn(200);
		when(res0.body()).thenReturn("chunk0".getBytes());

		when(res1.statusCode()).thenReturn(200);
		when(res1.body()).thenReturn("chunk1".getBytes());

		when(res2.statusCode()).thenReturn(200);
		when(res2.body()).thenReturn("chunk2".getBytes());

		when(adapter.buildRequest(
				any(),
				argThat(list -> list != null && list.contains("t0")),
				any(),
				any()
		)).thenReturn(req0);
		when(adapter.buildRequest(
				any(),
				argThat(list -> list != null && list.contains("t2")),
				any(),
				any()
		)).thenReturn(req1);
		when(adapter.buildRequest(
				any(),
				argThat(list -> list != null && list.contains("t4")),
				any(),
				any()
		)).thenReturn(req2);

		doReturn(res0).when(httpClient).send(eq(req0), any());
		doReturn(res1).when(httpClient).send(eq(req1), any());
		doReturn(res2).when(httpClient).send(eq(req2), any());

		when(adapter.parseResponse(eq("chunk0".getBytes()), any(), eq("embed-english-v3.0")))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(new float[]{0.0f}, new float[]{1.0f}), 4));
		when(adapter.parseResponse(eq("chunk1".getBytes()), any(), eq("embed-english-v3.0")))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(new float[]{2.0f}, new float[]{3.0f}), 4));
		when(adapter.parseResponse(eq("chunk2".getBytes()), any(), eq("embed-english-v3.0")))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(new float[]{4.0f}), 2));

		EmbeddingResponse response = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.cohere.com/v2/embed")
		);

		assertThat(response.data()).hasSize(5);
		for (int i = 0; i < 5; i++) {
			EmbeddingData item = response.data().get(i);
			assertThat(item.index()).isEqualTo(i);
			assertThat((float[]) item.embedding()).containsExactly((float) i);
		}
		assertThat(response.usage().promptTokens()).isEqualTo(10);
	}

	@Test
	@DisplayName("execute throws IOException when upstream returns error status")
	void executeUpstreamErrorThrows() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(List.of("text1"), "model", null, null, null);
		when(adapter.getMaxBatchSize()).thenReturn(10);

		HttpRequest mockHttpRequest = mock(HttpRequest.class);
		HttpResponse<byte[]> mockHttpResponse = mock(HttpResponse.class);

		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(mockHttpRequest);
		when(mockHttpResponse.statusCode()).thenReturn(500);
		when(mockHttpResponse.body()).thenReturn("Internal Server Error".getBytes());
		doReturn(mockHttpResponse).when(httpClient).send(any(), any());

		assertThatThrownBy(() -> orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		)).isInstanceOf(IOException.class)
		  .hasMessageContaining("Upstream embedding provider returned HTTP 500");
	}

	@Test
	@DisplayName("execute multi-batch wraps sub-batch failure into IOException")
	void executeMultiBatchFailure() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(List.of("t0", "t1", "t2"), "model", null, null, null);
		when(adapter.getMaxBatchSize()).thenReturn(1);

		HttpRequest mockReq = mock(HttpRequest.class);
		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(mockReq);
		when(httpClient.send(any(), any())).thenThrow(new IOException("Connection reset by peer"));

		assertThatThrownBy(() -> orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		)).isInstanceOf(IOException.class)
		  .hasMessageContaining("Connection reset by peer");
	}

	@Test
	@DisplayName("execute multi-batch wraps generic RuntimeException into IOException")
	void executeMultiBatchGenericFailure() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(List.of("t0", "t1", "t2"), "model", null, null, null);
		when(adapter.getMaxBatchSize()).thenReturn(1);

		HttpRequest mockReq = mock(HttpRequest.class);
		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(mockReq);
		when(httpClient.send(any(), any())).thenThrow(new IllegalStateException("Unexpected illegal state"));

		assertThatThrownBy(() -> orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		)).isInstanceOf(IOException.class)
		  .hasMessageContaining("Failed executing multi-batch embedding request");
	}

	@Test
	@DisplayName("execute multi-batch reassembles Base64 vectors correctly")
	void executeMultiBatchBase64() throws Exception {
		List<String> inputs = List.of("t0", "t1", "t2");
		EmbeddingRequest request = new EmbeddingRequest(inputs, "model", null, "base64", null);
		when(adapter.getMaxBatchSize()).thenReturn(2);

		HttpRequest req0 = mock(HttpRequest.class);
		HttpRequest req1 = mock(HttpRequest.class);
		HttpResponse<byte[]> res0 = mock(HttpResponse.class);
		HttpResponse<byte[]> res1 = mock(HttpResponse.class);

		when(res0.statusCode()).thenReturn(200);
		when(res0.body()).thenReturn("chunk0".getBytes());
		when(res1.statusCode()).thenReturn(200);
		when(res1.body()).thenReturn("chunk1".getBytes());

		when(adapter.buildRequest(
				any(),
				argThat(list -> list != null && list.contains("t0")),
				any(),
				any()
		)).thenReturn(req0);
		when(adapter.buildRequest(
				any(),
				argThat(list -> list != null && list.contains("t2")),
				any(),
				any()
		)).thenReturn(req1);

		doReturn(res0).when(httpClient).send(eq(req0), any());
		doReturn(res1).when(httpClient).send(eq(req1), any());

		when(adapter.parseResponse(eq("chunk0".getBytes()), any(), any()))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(new float[]{0.0f}, new float[]{1.0f}), 4));
		when(adapter.parseResponse(eq("chunk1".getBytes()), any(), any()))
				.thenReturn(NormalizedEmbeddingResult.ofFloats(List.of(new float[]{2.0f}), 2));

		EmbeddingResponse response = orchestrator.execute(
				request, adapter, providerConfig, URI.create("https://api.openai.com/v1/embeddings")
		);

		assertThat(response.data()).hasSize(3);
		assertThat(response.data().getFirst()
		                   .embedding()).isEqualTo(VectorEncodingUtils.encodeToBase64(new float[]{0.0f}));
		assertThat(response.data().get(1).embedding()).isEqualTo(VectorEncodingUtils.encodeToBase64(new float[]{1.0f}));
		assertThat(response.data().get(2).embedding()).isEqualTo(VectorEncodingUtils.encodeToBase64(new float[]{2.0f}));
	}

	@Test
	@DisplayName("execute multi-batch exception propagation for IOException and RuntimeException")
	void executeMultiBatchExceptions() throws Exception {
		EmbeddingRequest request = new EmbeddingRequest(
				List.of("t0", "t1", "t2", "t3"),
				"text-embedding-3-small", null, null, null
		);
		when(adapter.getMaxBatchSize()).thenReturn(2);

		HttpRequest req = mock(HttpRequest.class);
		when(adapter.buildRequest(any(), any(), any(), any())).thenReturn(req);
		doThrow(new IOException("Simulated network timeout")).when(httpClient).send(any(), any());

		assertThatThrownBy(() -> orchestrator.execute(
				request,
				adapter,
				providerConfig,
				URI.create("https://api.openai.com/v1/embeddings")
		))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Simulated network timeout");

		// Generic RuntimeException
		doThrow(new IllegalStateException("Unexpected crash")).when(httpClient).send(any(), any());
		assertThatThrownBy(() -> orchestrator.execute(
				request,
				adapter,
				providerConfig,
				URI.create("https://api.openai.com/v1/embeddings")
		))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Unexpected crash");
	}
}
