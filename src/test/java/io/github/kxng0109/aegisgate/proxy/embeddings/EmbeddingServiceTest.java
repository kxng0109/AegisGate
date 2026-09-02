package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("EmbeddingService")
@SuppressWarnings("DataFlowIssue")
class EmbeddingServiceTest {

	private final GatewayProperties gatewayProperties = new GatewayProperties();
	private final EmbeddingAdapterResolver adapterResolver = mock(EmbeddingAdapterResolver.class);
	private final EmbeddingBatchOrchestrator batchOrchestrator = mock(EmbeddingBatchOrchestrator.class);
	private final CostCalculator costCalculator = mock(CostCalculator.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private final EmbeddingService service = new EmbeddingService(
			gatewayProperties, adapterResolver, batchOrchestrator, costCalculator, eventPublisher
	);

	@Test
	@DisplayName("processEmbedding resolves alias, executes batch, calculates cost, and publishes ledger event")
	void processEmbeddingHappyPath() throws Exception {
		ProviderConfig provider = new ProviderConfig(
				"openai-main", ProviderType.OPENAI, URI.create("https://api.openai.com/v1"),
				new SensitiveString("key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		gatewayProperties.setProviders(Map.of("openai-main", provider));

		ModelAlias alias = new ModelAlias(
				List.of(new ProviderRef("openai-main", "text-embedding-3-small")),
				FailoverStrategy.SEQUENTIAL
		);
		gatewayProperties.setAliases(Map.of("text-embedding-3-small", alias));

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.OPENAI)).thenReturn(adapter);

		EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), "text-embedding-3-small", null, null, null);
		EmbeddingResponse mockResponse = EmbeddingResponse.of(
				"text-embedding-3-small",
				List.of(EmbeddingData.of(0, new float[]{0.1f})),
				10
		);

		when(batchOrchestrator.execute(eq(request), eq(adapter), eq(provider), any(URI.class)))
				.thenReturn(mockResponse);
		when(costCalculator.calculate(ProviderType.OPENAI, "text-embedding-3-small", 10, 0))
				.thenReturn(200L);

		EmbeddingResponse response = service.processEmbedding(request, "tenant-1");

		assertThat(response).isEqualTo(mockResponse);
		verify(eventPublisher).publishEvent(any(TokenUsageEvent.class));
		verify(costCalculator).calculate(ProviderType.OPENAI, "text-embedding-3-small", 10, 0);
	}

	@Test
	@DisplayName("processEmbedding falls back to provider matching name when no alias is configured")
	void processEmbeddingProviderFallback() throws Exception {
		ProviderConfig provider = new ProviderConfig(
				"ollama-local", ProviderType.OLLAMA, URI.create("http://localhost:11434"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(60)
		);
		gatewayProperties.setProviders(Map.of("ollama-local", provider));
		gatewayProperties.setAliases(Map.of());

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.OLLAMA)).thenReturn(adapter);

		EmbeddingRequest request = new EmbeddingRequest(List.of("text"), "ollama/nomic-embed-text", null, null, null);
		EmbeddingResponse mockResponse = EmbeddingResponse.of(
				"ollama/nomic-embed-text",
				List.of(EmbeddingData.of(0, new float[]{0.1f})),
				5
		);

		when(batchOrchestrator.execute(any(), any(), any(), any())).thenReturn(mockResponse);

		EmbeddingResponse response = service.processEmbedding(request, null);
		assertThat(response).isEqualTo(mockResponse);
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof TokenUsageEvent tue
				&& "unknown".equals(tue.ownerId())));
	}

	@Test
	@DisplayName("processEmbedding throws 400 Bad Request on blank model or empty inputs")
	void processEmbeddingValidationErrors() {
		// Blank model
		EmbeddingRequest blankModel = new EmbeddingRequest("input", "  ", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(blankModel, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Parameter 'model' is required");

		// Empty input
		EmbeddingRequest emptyInput = new EmbeddingRequest(List.of(), "model", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(emptyInput, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Parameter 'input' cannot be empty");

		// Batch size > 2048
		List<String> hugeBatch = Collections.nCopies(2049, "text");
		EmbeddingRequest hugeRequest = new EmbeddingRequest(hugeBatch, "model", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(hugeRequest, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("exceeds maximum allowed limit of 2048 items");
	}

	@Test
	@DisplayName("processEmbedding throws 404 Not Found when no providers are configured")
	void processEmbeddingNoProvidersThrows404() {
		gatewayProperties.setProviders(Map.of());
		gatewayProperties.setAliases(Map.of());

		EmbeddingRequest request = new EmbeddingRequest("text", "unknown-model", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(request, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("No upstream provider configured for embedding model");
	}

	@Test
	@DisplayName("processEmbedding wraps IOException into 502 Bad Gateway")
	void processEmbeddingUpstreamErrorThrows502() throws Exception {
		ProviderConfig provider = new ProviderConfig(
				"openai", ProviderType.OPENAI, URI.create("https://api.openai.com"),
				new SensitiveString("key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		gatewayProperties.setProviders(Map.of("openai", provider));

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.OPENAI)).thenReturn(adapter);
		when(batchOrchestrator.execute(any(), any(), any(), any())).thenThrow(new IOException("Connection reset"));

		EmbeddingRequest request = new EmbeddingRequest("text", "text-embedding-3-small", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(request, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Embedding upstream provider error")
				.hasCauseInstanceOf(IOException.class);

		// InterruptedException
		doThrow(new InterruptedException("Interrupted")).when(batchOrchestrator).execute(any(), any(), any(), any());
		assertThatThrownBy(() -> service.processEmbedding(request, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasCauseInstanceOf(InterruptedException.class);

		// Response with null usage
		EmbeddingResponse nullUsageResp = new EmbeddingResponse("list", List.of(), "text-embedding-3-small", null);
		doReturn(nullUsageResp).when(batchOrchestrator).execute(any(), any(), any(), any());
		EmbeddingResponse res = service.processEmbedding(request, "tenant-1");
		assertThat(res.usage()).isNull();
	}

	@Test
	@DisplayName("resolveEndpoint correctly joins base URI and subpaths")
	void resolveEndpointUriLogic() {
		assertThat(EmbeddingService.resolveEndpoint(URI.create("https://api.openai.com/v1"), "/v1/embeddings"))
				.isEqualTo(URI.create("https://api.openai.com/v1/embeddings"));

		assertThat(EmbeddingService.resolveEndpoint(URI.create("https://api.openai.com/v1/"), "/v1/embeddings"))
				.isEqualTo(URI.create("https://api.openai.com/v1/embeddings"));

		assertThat(EmbeddingService.resolveEndpoint(URI.create("http://localhost:11434"), "/api/embed"))
				.isEqualTo(URI.create("http://localhost:11434/api/embed"));

		assertThat(EmbeddingService.resolveEndpoint(URI.create("https://api.cohere.com/v2"), "v2/embed"))
				.isEqualTo(URI.create("https://api.cohere.com/v2/embed"));

		assertThat(EmbeddingService.resolveEndpoint(URI.create("https://api.cohere.com/v2/"), "/v2/embed"))
				.isEqualTo(URI.create("https://api.cohere.com/v2/embed"));
	}

	@Test
	@DisplayName("processEmbedding handles blank ownerId and direct fallback provider")
	void processEmbeddingBlankOwnerIdAndFirstProviderFallback() throws Exception {
		ProviderConfig provider = new ProviderConfig(
				"anthropic-main", ProviderType.ANTHROPIC, URI.create("https://api.anthropic.com"),
				new SensitiveString("key"), Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		gatewayProperties.setProviders(Map.of("anthropic-main", provider));
		gatewayProperties.setAliases(Map.of());

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.ANTHROPIC)).thenReturn(adapter);

		EmbeddingRequest request = new EmbeddingRequest(List.of("text"), "generic-embedding", null, null, null);
		EmbeddingResponse mockResponse = EmbeddingResponse.of(
				"generic-embedding",
				List.of(EmbeddingData.of(0, new float[]{0.1f})),
				5
		);

		when(batchOrchestrator.execute(any(), any(), any(), any())).thenReturn(mockResponse);

		EmbeddingResponse response = service.processEmbedding(request, "   ");
		assertThat(response).isEqualTo(mockResponse);
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof TokenUsageEvent tue
				&& "unknown".equals(tue.ownerId())));
	}

	@Test
	@DisplayName("processEmbedding handles alias with unconfigured provider and null model")
	void processEmbeddingAliasProviderMissing() throws Exception {
		ModelAlias alias = new ModelAlias(
				List.of(new ProviderRef("missing-provider", "text-embedding-3-small")),
				FailoverStrategy.SEQUENTIAL
		);
		ProviderConfig fallbackProvider = new ProviderConfig(
				"openai-fallback", ProviderType.OPENAI, URI.create("https://api.openai.com/v1"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		gatewayProperties.setAliases(Map.of("text-embedding-3-small", alias));
		gatewayProperties.setProviders(Map.of("openai-fallback", fallbackProvider));

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.OPENAI)).thenReturn(adapter);

		EmbeddingRequest request = new EmbeddingRequest(List.of("text"), "text-embedding-3-small", null, null, null);
		EmbeddingResponse mockResponse = new EmbeddingResponse("list", List.of(), "text-embedding-3-small", null);
		when(batchOrchestrator.execute(any(), any(), any(), any())).thenReturn(mockResponse);

		EmbeddingResponse response = service.processEmbedding(request, "tenant-1");
		assertThat(response).isEqualTo(mockResponse);

		// Null model
		EmbeddingRequest nullModel = new EmbeddingRequest(List.of("text"), null, null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(nullModel, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	@DisplayName("processEmbedding handles InterruptedException by setting interrupted flag")
	void processEmbeddingInterrupted() throws Exception {
		ProviderConfig provider = new ProviderConfig(
				"openai", ProviderType.OPENAI, URI.create("https://api.openai.com"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(30)
		);
		gatewayProperties.setProviders(Map.of("openai", provider));
		gatewayProperties.setAliases(Map.of());

		EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
		when(adapterResolver.resolve(ProviderType.OPENAI)).thenReturn(adapter);
		when(batchOrchestrator.execute(any(), any(), any(), any())).thenThrow(new InterruptedException("Interrupted"));

		EmbeddingRequest request = new EmbeddingRequest(List.of("text"), "openai-emb", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(request, "tenant-1"))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(Thread.currentThread().isInterrupted()).isTrue());
	}

	@Test
	@DisplayName("resolveEndpoint handles various base and path formats")
	void testResolveEndpointVariants() {
		URI uri1 = EmbeddingService.resolveEndpoint(URI.create("https://api.openai.com/v1/"), "/v1/embeddings");
		assertThat(uri1).isEqualTo(URI.create("https://api.openai.com/v1/embeddings"));

		URI uri2 = EmbeddingService.resolveEndpoint(URI.create("https://api.cohere.com/v2"), "v2/embed");
		assertThat(uri2).isEqualTo(URI.create("https://api.cohere.com/v2/embed"));

		URI uri3 = EmbeddingService.resolveEndpoint(URI.create("https://api.deepseek.com"), "v1/embeddings");
		assertThat(uri3).isEqualTo(URI.create("https://api.deepseek.com/v1/embeddings"));

		URI uri4 = EmbeddingService.resolveEndpoint(URI.create("https://api.example.com/v2/"), "/v2/custom");
		assertThat(uri4).isEqualTo(URI.create("https://api.example.com/v2/custom"));
	}

	@Test
	@DisplayName("validateRequest rejects oversized batch inputs exceeding 2048 items")
	void testValidateRequestOversizedBatch() {
		List<String> bigList = Collections.nCopies(2049, "sample text");
		EmbeddingRequest request = new EmbeddingRequest(bigList, "text-emb", null, null, null);
		assertThatThrownBy(() -> service.processEmbedding(request, "t1"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("exceeds maximum allowed limit");
	}

	@Test
	@DisplayName("processEmbedding works for DeepSeek, Gemini, and Vertex AI provider types")
	void processEmbeddingNewProviderTypes() throws Exception {
		for (ProviderType type : List.of(ProviderType.DEEPSEEK, ProviderType.GEMINI, ProviderType.VERTEX_AI)) {
			ProviderConfig provider = new ProviderConfig(
					type.name().toLowerCase() + "-main", type, URI.create("https://api.example.com"),
					null, Duration.ofSeconds(5), Duration.ofSeconds(30)
			);
			gatewayProperties.setProviders(Map.of(type.name().toLowerCase() + "-main", provider));
			gatewayProperties.setAliases(Map.of());

			EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
			when(adapterResolver.resolve(type)).thenReturn(adapter);

			EmbeddingRequest request = new EmbeddingRequest(
					List.of("text"),
					type.name().toLowerCase() + "-embed",
					null,
					null,
					null
			);
			EmbeddingResponse mockResponse = EmbeddingResponse.of(type.name().toLowerCase() + "-embed", List.of(), 5);
			when(batchOrchestrator.execute(any(), any(), any(), any())).thenReturn(mockResponse);

			EmbeddingResponse response = service.processEmbedding(request, "tenant-1");
			assertThat(response).isEqualTo(mockResponse);
		}
	}
}
