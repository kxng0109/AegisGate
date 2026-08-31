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
import io.github.kxng0109.aegisgate.proxy.sse.*;
import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;

/**
 * Unit tests for {@link ProxyController}: request validation, alias resolution, streaming of the winning provider's SSE
 * response, upstream error passthrough, exception mapping, and the usage event published to the asynchronous ledger
 * after a completed stream.
 */
@DisplayName("ProxyController")
@SuppressWarnings("DataFlowIssue")
class ProxyControllerTest {

	private static final String PATH_BODY = "{\"model\":\"gpt-5.6-luna\",\"messages\":[]}";
	private static final String USAGE_BODY = "{\"model\":\"gpt-5.6-luna\",\"messages\":[],"
			+ "\"stream_options\":{\"include_usage\":true}}";

	private final FailoverOrchestrator orchestrator = mock(FailoverOrchestrator.class);
	private final GatewayProperties gatewayProperties = new GatewayProperties();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CostCalculator costCalculator = mock(CostCalculator.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final SseFlushStrategy flushStrategy = mock(SseFlushStrategy.class);
	private final SseLineGuardAutoConfig.SseLineGuardFactory lineGuardFactory = mock(SseLineGuardAutoConfig.SseLineGuardFactory.class);

	{
		when(flushStrategy.register(any())).thenReturn(null);
		SseLineGuard noopGuard = new SseLineGuard() {
			@Override
			public List<String> checkLine(String line, SseLineGuard.ProviderType provider) {
				return List.of(line);
			}

			@Override
			public boolean isRejected() {
				return false;
			}

			@Override
			public void onStreamComplete() {
			}

			@Override
			public void onStreamAbort(String reason) {
			}

			@Override
			public SseLineGuard.ConfigSnapshot config() {
				return new SseLineGuard.ConfigSnapshot(
						16384,
						Map.of(),
						10,
						SseLineGuard.Action.REJECT_LINE_AND_CLOSE
				);
			}
		};
		// The factory's newGuard returns DefaultSseLineGuard; we mock it to return a no-op guard.
		DefaultSseLineGuard noopDefault = mock(DefaultSseLineGuard.class);
		when(noopDefault.checkLine(anyString(), any(SseLineGuard.ProviderType.class)))
				.thenAnswer(inv -> List.of((String) inv.getArgument(0)));
		when(noopDefault.isRejected()).thenReturn(false);
		when(lineGuardFactory.newGuard(
				any(SseLineGuard.ProviderType.class),
				anyString(),
				any()
		)).thenReturn(noopDefault);
	}

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
				resolver, costCalculator, eventPublisher, flushStrategy, lineGuardFactory
		);
	}

	@Test
	@DisplayName("an unparseable body is rejected with 400")
	void unparseableJsonBodyRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("not-json", request());
		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non-object json body is rejected with 400")
	void nonObjectJsonBodyRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("123", request());
		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non-string model field is rejected with 400")
	void nonStringModelRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("{\"model\": 123}", request());
		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("an orchestrator exception with null cause is wrapped as upstream unavailable")
	void orchestratorNullCauseWrapped() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(new CompletionException(null)));
		assertThrows(
				UpstreamUnavailableException.class, () ->
						controller.proxyChatCompletions(PATH_BODY, request())
		);
	}

	@Test
	@DisplayName("an orchestrator exception with UpstreamUnavailableException is rethrown directly")
	void orchestratorUpstreamUnavailableRethrown() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(
						new CompletionException(new UpstreamUnavailableException("direct", null, false, false))
				));
		UpstreamUnavailableException thrown = assertThrows(
				UpstreamUnavailableException.class, () ->
						controller.proxyChatCompletions(PATH_BODY, request())
		);
		assertEquals("direct", thrown.getMessage());
	}

	@Test
	@DisplayName("an orchestrator exception with non-null non-upstream cause is wrapped")
	void orchestratorGenericCauseWrapped() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(
						new CompletionException(new RuntimeException("general network error"))
				));
		UpstreamUnavailableException thrown = assertThrows(
				UpstreamUnavailableException.class, () ->
						controller.proxyChatCompletions(PATH_BODY, request())
		);
		assertEquals("upstream request failed unexpectedly", thrown.getMessage());
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
	void missingModelRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"messages\":[]}",
				request()
		);

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("an unknown model is rejected with 404")
	void unknownModelRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":\"nope\"}",
				request()
		);

		assertEquals(404, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("the orchestrator is called with the resolved alias and the raw body")
	void orchestratorReceivesAliasAndBody() {
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
	void nullBodyRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(null, request());

		assertEquals(400, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("a non object body is rejected with 400")
	void nonObjectBodyRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("[1,2,3]", request());

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non textual model is rejected with 400")
	void nonTextualModelRejected() {
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
	void noEventWithoutUsage() {
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
	void arrayModelRejected() {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":[\"a\"]}",
				request()
		);
		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non streaming body with a model passes validation")
	void nonStreamingBodyAccepted() {
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

	@Test
	@DisplayName("a servlet output stream is registered, reported per line, and unregistered")
	void relaySseDrivesTheFlushStrategyLifecycle() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: {\"content\":\" world\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		SseFlushStrategy.FlushHandle handle = mock(SseFlushStrategy.FlushHandle.class);
		when(flushStrategy.register(any())).thenReturn(handle);
		when(flushStrategy.onWrite(any(), anyInt())).thenReturn(false);

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.writtenUtf8().contains("data: {\"content\":\"hello\"}"));
		verify(flushStrategy).register(any());
		verify(flushStrategy, times(3)).onWrite(any(), anyInt());
		verify(flushStrategy).unregister(handle);
	}

	@Test
	@DisplayName("a backpressure abort stops the stream and still unregisters the handle")
	void backpressureAbortStopsTheStream() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: {\"content\":\" world\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		SseFlushStrategy.FlushHandle handle = mock(SseFlushStrategy.FlushHandle.class);
		when(flushStrategy.register(any())).thenReturn(handle);
		when(flushStrategy.onWrite(any(), anyInt())).thenReturn(true);

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.writtenUtf8().contains("data: {\"content\":\"hello\"}"));
		verify(flushStrategy, times(1)).onWrite(any(), anyInt());
		verify(flushStrategy).unregister(handle);
	}

	@Test
	@DisplayName("a connection-limit rejection sheds the stream without touching the flush path")
	void connectionLimitRejectionShedsTheStream() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));
		when(flushStrategy.register(any())).thenThrow(new SseConnectionLimitException("limit"));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertEquals("", out.writtenUtf8());
		verify(flushStrategy, never()).onWrite(any(), anyInt());
		verify(flushStrategy, never()).unregister(any());
	}

	@Test
	@DisplayName("a line-too-long error event is written and the stream aborted when guard rejects")
	void guardRejectionEmitsErrorEvent() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"oversized\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuard rejectingGuard =
				mock(io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuard.class);
		when(rejectingGuard.checkLine(anyString(), any()))
				.thenReturn(List.of("event: error", "data: {\"code\":\"LINE_TOO_LONG\"}", ""));
		when(rejectingGuard.isRejected()).thenReturn(true);
		when(lineGuardFactory.newGuard(any(), anyString(), any())).thenReturn(rejectingGuard);

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.writtenUtf8().contains("event: error"));
		assertTrue(out.writtenUtf8().contains("LINE_TOO_LONG"));
		verify(rejectingGuard).onStreamAbort("line_too_long");
	}

	@Test
	@DisplayName("a line dropped by REJECT_LINE_CONTINUE is skipped")
	void guardContinueSkipsLine() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"dropped\"}", "data: {\"content\":\"kept\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuard dropGuard =
				mock(io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuard.class);
		when(dropGuard.checkLine(eq("data: {\"content\":\"dropped\"}"), any()))
				.thenReturn(List.of());
		when(dropGuard.checkLine(eq("data: {\"content\":\"kept\"}"), any()))
				.thenReturn(List.of("data: {\"content\":\"kept\"}"));
		when(dropGuard.checkLine(eq("data: [DONE]"), any()))
				.thenReturn(List.of("data: [DONE]"));
		when(dropGuard.isRejected()).thenReturn(false);
		when(lineGuardFactory.newGuard(any(), anyString(), any())).thenReturn(dropGuard);

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertFalse(out.writtenUtf8().contains("dropped"));
		assertTrue(out.writtenUtf8().contains("kept"));
	}

	@Test
	@DisplayName("LineTooLongException from the body stream writes SSE error event")
	void bodyStreamLineTooLongExceptionWritesSseError() throws Exception {
		Stream<String> throwingStream = Stream.generate(() -> {
			throw new io.github.kxng0109.aegisgate.proxy.sse.LineTooLongException(100, 200, "openai");
		});
		ProviderResponse response = providerResponse("openai", 200, sseHeaders(), throwingStream);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.writtenUtf8().contains("event: error"));
		assertTrue(out.writtenUtf8().contains("LINE_TOO_LONG"));
	}

	@Test
	@DisplayName("relaySse writes to non-ServletOutputStream without flush strategy")
	void relaySseWithNonServletOutputStream() throws Exception {
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"plain\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.toString(StandardCharsets.UTF_8).contains("data: {\"content\":\"plain\"}"));
	}

	@Test
	@DisplayName("blank model string returns 400 bad request")
	void blankModelReturnsBadRequest() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":\"   \"}",
				request()
		);
		assertEquals(400, response.getStatusCode().value());
		assertTrue(body(response).contains("model is required"));
	}

	@Test
	@DisplayName("normalizer specific upstream model overrides requested alias in ledger event")
	void normalizerSpecificUpstreamModelUsedInLedger() throws Exception {
		// Use Anthropic dialect where upstream model is captured from message_start
		ProviderConfig anthropicConfig = new ProviderConfig(
				"anthropic-p", ProviderType.ANTHROPIC, URI.create("https://api.anthropic.com"),
				new SensitiveString("sk-ant"), Duration.ofSeconds(5), Duration.ofSeconds(60)
		);
		Map<String, ProviderConfig> newProviders = new LinkedHashMap<>(gatewayProperties.getProviders());
		newProviders.put("anthropic-p", anthropicConfig);
		gatewayProperties.setProviders(newProviders);

		ModelAlias anthropicAlias = new ModelAlias(
				List.of(new ProviderRef("anthropic-p", null)),
				FailoverStrategy.SEQUENTIAL
		);
		Map<String, ModelAlias> newAliases = new LinkedHashMap<>(gatewayProperties.getAliases());
		newAliases.put("claude-alias", anthropicAlias);
		gatewayProperties.setAliases(newAliases);

		Stream<String> sseLines = Stream.of(
				"event: message_start",
				"data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-5-20260601\",\"usage\":{\"input_tokens\":10}}}",
				"event: message_delta",
				"data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}",
				"event: message_stop",
				"data: {\"type\":\"message_stop\"}"
		);
		ProviderResponse providerResp = providerResponse("anthropic-p", 200, sseHeaders(), sseLines);
		when(orchestrator.execute(any(), anyString())).thenReturn(CompletableFuture.completedFuture(providerResp));

		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(
				"{\"model\":\"claude-alias\",\"messages\":[],\"stream_options\":{\"include_usage\":true}}",
				request()
		);
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		response.getBody().writeTo(out);

		ArgumentCaptor<TokenUsageEvent> eventCaptor = ArgumentCaptor.forClass(TokenUsageEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertEquals("claude-sonnet-5-20260601", eventCaptor.getValue().model());
	}

	@Test
	@DisplayName("relaySse with ServletOutputStream when flushHandle is null flushes directly")
	void relaySseWithServletOutputStreamNullFlushHandle() throws Exception {
		when(flushStrategy.register(any())).thenReturn(null);
		ProviderResponse response = providerResponse(
				"openai", 200, sseHeaders(),
				Stream.of("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY, request());
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		responseEntity.getBody().writeTo(out);

		assertTrue(out.writtenUtf8().contains("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"));
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