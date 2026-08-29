package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import io.github.kxng0109.aegisgate.proxy.protocol.AnthropicAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.OllamaAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiPassthroughAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapterResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProxyController}: request validation, alias resolution, streaming of the winning provider's SSE
 * response, upstream error passthrough, exception mapping, and the usage event published to the asynchronous ledger
 * after a completed stream.
 */
@DisplayName("ProxyController")
class ProxyControllerTest {

	private static final String PATH_BODY = "{\"model\":\"gpt-5.6-luna\",\"messages\":[]}";
	private static final String USAGE_BODY = "{\"model\":\"gpt-5.6-luna\",\"messages\":[],"
			+ "\"stream_options\":{\"include_usage\":true}}";

	private final FailoverOrchestrator orchestrator = mock(FailoverOrchestrator.class);
	private final GatewayProperties gatewayProperties = new GatewayProperties();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CostCalculator costCalculator = mock(CostCalculator.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private ProxyController controller;

	@BeforeEach
	void setUp() {
		gatewayProperties.setAliases(Map.of(
				"gpt-5.6-luna", new ModelAlias(
						List.of(new ProviderRef("openai", null)), FailoverStrategy.SEQUENTIAL)
		));
		gatewayProperties.setProviders(Map.of(
				"openai", new ProviderConfig(
						"openai", ProviderType.OPENAI, URI.create("https://api.openai.com"),
						new SensitiveString("sk-test"), Duration.ofSeconds(3), Duration.ofSeconds(30)
				)
		));
		ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
				new OpenAiPassthroughAdapter(objectMapper),
				new AnthropicAdapter(objectMapper),
				new OllamaAdapter(objectMapper)
		);
		controller = new ProxyController(
				orchestrator, gatewayProperties, objectMapper,
				resolver, costCalculator, eventPublisher
		);
	}

	@Test
	@DisplayName("an empty body is rejected with 400")
	void emptyBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("", request());

		assertEquals(400, response.getStatusCode().value());
		assertTrue(body(response).contains("empty request body"));
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("a body without a model is rejected with 400")
	void missingModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"messages\":[]}",
				request()
		);

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("an unknown model is rejected with 404")
	void unknownModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":\"nope\"}",
				request()
		);

		assertEquals(404, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("the orchestrator is called with the resolved alias and the raw body")
	void orchestratorReceivesAliasAndBody() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"a\":1}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		controller.proxyChatCompletions(PATH_BODY, request());

		ArgumentCaptor<ModelAlias> aliasCaptor = ArgumentCaptor.forClass(ModelAlias.class);
		verify(orchestrator).execute(aliasCaptor.capture(), anyString());
		assertEquals(gatewayProperties.getAliases().get("gpt-5.6-luna"), aliasCaptor.getValue());
	}

	@Test
	@DisplayName("the winning SSE stream is relayed with event stream headers")
	void streamsWinningResponse() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		assertEquals(200, responseEntity.getStatusCode().value());
		assertTrue(responseEntity.getHeaders().getContentType().toString().contains("text/event-stream"));
		String streamed = body(responseEntity);
		assertTrue(streamed.contains("data: {\"content\":\"hello\"}"));
		assertTrue(streamed.contains("data: [DONE]"));
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("a non 200 upstream response is passed through with its status")
	void passthroughNon200() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 429, jsonHeaders(),
				Stream.of("{\"error\":{\"message\":\"rate limited\"}}")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		assertEquals(429, responseEntity.getStatusCode().value());
		assertTrue(body(responseEntity).contains("rate limited"));
	}

	@Test
	@DisplayName("an upstream failure is rethrown for the exception handler")
	void rethrowsUpstreamFailure() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(
						new UpstreamUnavailableException("all failed", null, false, false, 401)));

		assertThrows(
				UpstreamUnavailableException.class,
				() -> controller.proxyChatCompletions(PATH_BODY, request())
		);
	}

	@Test
	@DisplayName("an unexpected failure is wrapped as an upstream failure")
	void wrapsUnexpectedFailure() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

		UpstreamUnavailableException failure = assertThrows(
				UpstreamUnavailableException.class,
				() -> controller.proxyChatCompletions(PATH_BODY, request())
		);

		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	@DisplayName("a completion exception without a cause is still mapped")
	void completionExceptionWithoutCause() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(new CompletionException(null)));

		UpstreamUnavailableException failure = assertThrows(
				UpstreamUnavailableException.class,
				() -> controller.proxyChatCompletions(PATH_BODY, request())
		);

		assertNull(failure.getCause());
	}

	@Test
	@DisplayName("an unknown winning provider defaults to the OpenAI protocol")
	void unknownProviderDefaultsToOpenAi() throws Exception {
		ProviderResponse response = providerResponse(
				"ghost", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hi\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		assertEquals(200, responseEntity.getStatusCode().value());
		assertTrue(body(responseEntity).contains("data: {\"content\":\"hi\"}"));
	}

	@Test
	@DisplayName("a stream that never signals done still relays its lines")
	void streamWithoutDoneStillRelays() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: {\"content\":\" world\"}")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		String streamed = body(controller.proxyChatCompletions(PATH_BODY, request()));

		assertTrue(streamed.contains("data: {\"content\":\"hello\"}"));
		assertTrue(streamed.contains("data: {\"content\":\" world\"}"));
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("a null body is rejected with 400")
	void nullBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(null, request());

		assertEquals(400, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("a non object body is rejected with 400")
	void nonObjectBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("[1,2,3]", request());

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non textual model is rejected with 400")
	void nonTextualModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("{\"model\":123}", request());

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a downstream write failure is swallowed so the stream closes cleanly")
	void downstreamFailureIsSwallowed() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		responseEntity.getBody().writeTo(failing);
	}

	@Test
	@DisplayName("a downstream write failure in relaySse is swallowed")
	void downstreamSseWriteFailureSwallowed() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		responseEntity.getBody().writeTo(failing);
	}

	@Test
	@DisplayName("a downstream write failure in relayRaw is swallowed")
	void downstreamRawWriteFailureSwallowed() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 429, jsonHeaders(),
				Stream.of("{\"error\":{\"message\":\"rate limited\"}}")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		responseEntity.getBody().writeTo(failing);
	}

	@Test
	@DisplayName("usage captured at the end of a stream is published once to the ledger")
	void publishesUsageEventAfterStream() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of(
						"data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}",
						"data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.6-luna\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}",
						"data: [DONE]"
				)
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		when(costCalculator.calculate(ProviderType.OPENAI, "gpt-5.6-luna", 10, 5)).thenReturn(4200L);

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(USAGE_BODY, request());

		assertTrue(body(responseEntity).contains("data: [DONE]"));
		ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		TokenUsageEvent event = captor.getValue();
		assertEquals("gpt-5.6-luna", event.model());
		assertEquals("openai", event.provider());
		assertEquals(10, event.promptTokens());
		assertEquals(5, event.completionTokens());
		assertEquals(15, event.totalTokens());
		assertEquals(4200, event.costUsdMicros());
		assertEquals("owner-1", event.ownerId());
	}

	@Test
	@DisplayName("no event is published when the stream carried no usage")
	void noEventWithoutUsage() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		controller.proxyChatCompletions(PATH_BODY, request());

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("a model node that is an array is rejected")
	void arrayModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":[\"a\"]}",
				request()
		);
		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non streaming body with a model passes validation")
	void nonStreamingBodyAccepted() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		String body = "{\"model\":\"gpt-5.6-luna\",\"messages\":[],\"stream\":false}";

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(body, request());

		assertEquals(200, responseEntity.getStatusCode().value());
		verify(orchestrator).execute(any(), anyString());
	}

	@Test
	@DisplayName("an unparseable stream options field does not break the request")
	void unparseableStreamOptionsIgnored() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		String body = "{\"model\":\"gpt-5.6-luna\",\"messages\":42,\"stream_options\":42}";

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(body, request());

		assertEquals(200, responseEntity.getStatusCode().value());
		assertTrue(body(responseEntity).contains("data: [DONE]"));
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("usage falls back to the requested model when the chunk carries none")
	void usageFallsBackToRequestedModel() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of(
						"data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}",
						"data: [DONE]"
				)
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		when(costCalculator.calculate(ProviderType.OPENAI, "gpt-5.6-luna", 2, 3)).thenReturn(88L);

		body(controller.proxyChatCompletions(PATH_BODY, request()));

		ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertEquals("gpt-5.6-luna", captor.getValue().model());
	}

	@Test
	@DisplayName("the usage chunk stays internal when the client did not ask for it")
	void usageChunkNotForwardedWhenNotRequested() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of(
						"data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}",
						"data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.6-luna\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}",
						"data: [DONE]"
				)
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		when(costCalculator.calculate(ProviderType.OPENAI, "gpt-5.6-luna", 10, 5)).thenReturn(4200L);

		String streamed = body(controller.proxyChatCompletions(PATH_BODY, request()));

		assertFalse(streamed.contains("\"usage\""), "the usage chunk must not reach a client that did not ask");
		verify(eventPublisher).publishEvent(any(TokenUsageEvent.class));
	}

	@Test
	@DisplayName("a missing owner id is carried through as null")
	void missingOwnerId() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of(
						"data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
						"data: [DONE]"
				)
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		when(costCalculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(0L);

		MockHttpServletRequest anonymous = new MockHttpServletRequest();
		body(controller.proxyChatCompletions(PATH_BODY, anonymous));

		ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertNull(captor.getValue().ownerId());
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("aegis.ownerId", "owner-1");
		return request;
	}

	@SuppressWarnings("unchecked")
	private static ProviderResponse providerResponse(
			String provider,
			int status,
			HttpHeaders headers,
			Stream<String> lines
	) {
		HttpResponse<Stream<String>> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.headers()).thenReturn(headers);
		when(response.body()).thenReturn(lines);
		return new ProviderResponse(provider, response);
	}

	private static HttpHeaders sseHeaders() {
		return HttpHeaders.of(Map.of("Content-Type", List.of("text/event-stream")), (n, v) -> true);
	}

	private static HttpHeaders jsonHeaders() {
		return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (n, v) -> true);
	}

	private static String body(ResponseEntity<StreamingResponseBody> response) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		response.getBody().writeTo(out);
		return out.toString(StandardCharsets.UTF_8);
	}
}