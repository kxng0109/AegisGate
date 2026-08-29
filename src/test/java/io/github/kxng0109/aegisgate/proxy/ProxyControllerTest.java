package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.contracts.FailoverStrategy;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProxyController}: request validation, alias
 * resolution, streaming of the winning provider's SSE response, upstream
 * error passthrough, and exception mapping.
 */
@DisplayName("ProxyController")
class ProxyControllerTest {

	private static final String PATH_BODY = "{\"model\":\"gpt-5.6-luna\",\"messages\":[]}";

	private final FailoverOrchestrator orchestrator = mock(FailoverOrchestrator.class);
	private final GatewayProperties gatewayProperties = new GatewayProperties();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private ProxyController controller;

	@BeforeEach
	void setUp() {
		gatewayProperties.setAliases(Map.of(
				"gpt-5.6-luna", new ModelAlias(
						List.of(new ProviderRef("openai", null)), FailoverStrategy.SEQUENTIAL)
		));
		controller = new ProxyController(orchestrator, gatewayProperties, objectMapper);
	}

	@Test
	@DisplayName("an empty body is rejected with 400")
	void emptyBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("");

		assertEquals(400, response.getStatusCode().value());
		assertTrue(body(response).contains("empty request body"));
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("a body without a model is rejected with 400")
	void missingModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("{\"messages\":[]}");

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("an unknown model is rejected with 404")
	void unknownModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("{\"model\":\"nope\"}");

		assertEquals(404, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("the orchestrator is called with the resolved alias and the raw body")
	void orchestratorReceivesAliasAndBody() throws Exception {
		ProviderResponse response = providerResponse("openai", 200, sseHeaders(),
		                                             Stream.of("data: {\"a\":1}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		controller.proxyChatCompletions(PATH_BODY);

		ArgumentCaptor<ModelAlias> aliasCaptor = ArgumentCaptor.forClass(ModelAlias.class);
		verify(orchestrator).execute(aliasCaptor.capture(), anyString());
		assertEquals(gatewayProperties.getAliases().get("gpt-5.6-luna"), aliasCaptor.getValue());
	}

	@Test
	@DisplayName("the winning SSE stream is relayed with event stream headers")
	void streamsWinningResponse() throws Exception {
		ProviderResponse response = providerResponse("openai", 200, sseHeaders(),
		                                             Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY);

		assertEquals(200, responseEntity.getStatusCode().value());
		assertTrue(responseEntity.getHeaders().getContentType().toString().contains("text/event-stream"));
		String streamed = body(responseEntity);
		assertTrue(streamed.contains("data: {\"content\":\"hello\"}"));
		assertTrue(streamed.contains("data: [DONE]"));
	}

	@Test
	@DisplayName("a non 200 upstream response is passed through with its status")
	void passthroughNon200() throws Exception {
		ProviderResponse response = providerResponse("openai", 429, jsonHeaders(),
		                                             Stream.of("{\"error\":{\"message\":\"rate limited\"}}")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY);

		assertEquals(429, responseEntity.getStatusCode().value());
		assertTrue(body(responseEntity).contains("rate limited"));
	}

	@Test
	@DisplayName("an upstream failure is rethrown for the exception handler")
	void rethrowsUpstreamFailure() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(
						new UpstreamUnavailableException("all failed", null, false, false, 401)));

		assertThrows(UpstreamUnavailableException.class,
		             () -> controller.proxyChatCompletions(PATH_BODY)
		);
	}

	@Test
	@DisplayName("an unexpected failure is wrapped as an upstream failure")
	void wrapsUnexpectedFailure() {
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
		                                                    () -> controller.proxyChatCompletions(PATH_BODY)
		);

		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	@DisplayName("a null body is rejected with 400")
	void nullBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions(null);

		assertEquals(400, response.getStatusCode().value());
		verify(orchestrator, never()).execute(any(), anyString());
	}

	@Test
	@DisplayName("a non object body is rejected with 400")
	void nonObjectBodyRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("[1,2,3]");

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a non textual model is rejected with 400")
	void nonTextualModelRejected() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.proxyChatCompletions("{\"model\":123}");

		assertEquals(400, response.getStatusCode().value());
	}

	@Test
	@DisplayName("a downstream write failure is swallowed so the stream closes cleanly")
	void downstreamFailureIsSwallowed() throws Exception {
		ProviderResponse response = providerResponse("openai", 200, sseHeaders(),
		                                             Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]")
		);
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY);

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
		ProviderResponse response = providerResponse("openai", 200, sseHeaders(),
				Stream.of("data: {\"content\":\"hello\"}", "data: [DONE]"));
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY);

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
		ProviderResponse response = providerResponse("openai", 429, jsonHeaders(),
				Stream.of("{\"error\":{\"message\":\"rate limited\"}}"));
		when(orchestrator.execute(any(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(response));

		ResponseEntity<StreamingResponseBody> responseEntity = controller.proxyChatCompletions(PATH_BODY);

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		responseEntity.getBody().writeTo(failing);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

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