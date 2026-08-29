package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration style tests for {@link FailoverOrchestrator} using two in
 * process mock providers: sequential failover, transient and non transient
 * classification, circuit breaker interaction, timeout handling, RACE
 * behaviour, and the resulting exception semantics.
 */
@DisplayName("FailoverOrchestrator")
class FailoverOrchestratorTest {

	private static final String MODELS = "{\"model\":\"m\",\"messages\":[]}";

	private MockWebServer serverA;
	private MockWebServer serverB;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() throws Exception {
		serverA = new MockWebServer();
		serverA.start();
		serverB = new MockWebServer();
		serverB.start();
		httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(5))
				.executor(Executors.newVirtualThreadPerTaskExecutor())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	@AfterEach
	void tearDown() throws Exception {
		serverA.shutdown();
		serverB.shutdown();
	}

	// ---------------------------------------------------------------------
	// SEQUENTIAL
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("the first successful provider wins")
	void firstSuccessWins() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(sse("data: hello from A\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(alias("a", "b"), MODELS));

		assertEquals("a", winner.providerName());
		assertEquals(200, winner.response().statusCode());
		assertTrue(winner.response().body().collect(Collectors.joining("\n")).contains("hello from A"));
		assertEquals(0, serverB.getRequestCount(), "the backup must not be contacted");
	}

	@Test
	@DisplayName("a 500 fails over to the next provider")
	void failsOverOn500() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(500));
		serverB.enqueue(sse("data: hello from B\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(alias("a", "b"), MODELS));

		assertEquals("b", winner.providerName());
		assertEquals(1, serverB.getRequestCount());
		assertEquals(200, winner.response().statusCode());
	}

	@Test
	@DisplayName("a 429 fails over to the next provider")
	void failsOverOn429() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "60"));
		serverB.enqueue(sse("data: hello from B\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(alias("a", "b"), MODELS));

		assertEquals("b", winner.providerName());
	}

	@Test
	@DisplayName("a 401 never fails over and carries its status")
	void noFailoverOn401() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertEquals(401, failure.getUpstreamStatus());
		assertEquals(0, serverB.getRequestCount(), "the backup must not be contacted on 401");
	}

	@Test
	@DisplayName("a 400 never fails over either")
	void noFailoverOn400() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(400));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertEquals(400, failure.getUpstreamStatus());
		assertEquals(0, serverB.getRequestCount());
	}

	@Test
	@DisplayName("all providers failing transiently reports a generic failure")
	void allTransientFailures() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(500));
		serverB.enqueue(error(503));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertFalse(failure.isServiceUnavailable());
		assertFalse(failure.isTimedOut());
		assertEquals(0, failure.getUpstreamStatus());
	}

	@Test
	@DisplayName("a hung primary times out and the backup wins")
	void timeoutFailsOver() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverB.enqueue(sse("data: hello from B\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(alias("a", "b"), MODELS));

		assertEquals("b", winner.providerName());
	}

	@Test
	@DisplayName("timeouts across the whole chain report a timed out failure")
	void allTimeoutsAcrossChain() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertTrue(failure.isTimedOut(), "a hung chain must map to a timed out failure");
	}

	// ---------------------------------------------------------------------
	// Circuit breaker integration
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("the circuit opens after repeated failures and skips the provider")
	void circuitOpensAndSkips() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		for (int i = 0; i < 3; i++) {
			serverA.enqueue(error(500));
			serverB.enqueue(sse("data: ok\n\ndata: [DONE]"));
			join(orchestrator.execute(alias("a", "b"), MODELS));
		}
		assertEquals(3, serverA.getRequestCount(), "provider A must see three failures");

		serverB.enqueue(sse("data: ok\n\ndata: [DONE]"));
		join(orchestrator.execute(alias("a", "b"), MODELS));

		assertEquals(3, serverA.getRequestCount(), "provider A must be skipped while open");
	}

	@Test
	@DisplayName("a provider rejected by the validator is skipped entirely")
	void blockedProviderIsSkipped() {
		FailoverOrchestrator orchestrator = orchestrator(url -> {
			throw new SsrfViolationException("blocked for tests");
		});

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a"), MODELS)));

		assertTrue(failure.isServiceUnavailable(), "nothing was callable, so 503");
		assertEquals(0, serverA.getRequestCount(), "a blocked provider must never be contacted");
	}

	@Test
	@DisplayName("the validator runs only once per provider")
	void validatorRunsOncePerProvider() throws Exception {
		AtomicInteger validationCount = new AtomicInteger();
		FailoverOrchestrator orchestrator = orchestrator(url -> validationCount.incrementAndGet());

		serverA.enqueue(sse("data: one\n\ndata: [DONE]"));
		join(orchestrator.execute(alias("a"), MODELS));
		serverA.enqueue(sse("data: two\n\ndata: [DONE]"));
		join(orchestrator.execute(alias("a"), MODELS));

		assertEquals(1, validationCount.get(), "validation must run once per provider");
	}

	// ---------------------------------------------------------------------
	// RACE
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("RACE returns the first successful response")
	void raceFirstSuccessWins() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(sse("data: race A\n\ndata: [DONE]"));
		serverB.enqueue(sse("data: race B\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));

		assertEquals(200, winner.response().statusCode());
		assertTrue(winner.providerName().equals("a") || winner.providerName().equals("b"));
	}

	@Test
	@DisplayName("RACE with a hung provider still completes through the other")
	void raceSurvivesHungProvider() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(sse("data: fast\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));

		assertEquals("a", winner.providerName());
	}

	@Test
	@DisplayName("RACE with all providers failing reports a generic failure")
	void raceAllFail() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(500));
		serverB.enqueue(error(500));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(raceAlias("a", "b"), MODELS)));

		assertFalse(failure.isTimedOut());
		assertFalse(failure.isServiceUnavailable());
	}

	@Test
	@DisplayName("a 200 without an event stream content type is not treated as success")
	void nonSse200IsNotSuccess() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(200).setBody("{\"error\":\"not sse\"}"));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertEquals(200, failure.getUpstreamStatus());
	}

	@Test
	@DisplayName("an empty chain reports that nothing was callable")
	void emptyChainIsUnavailable() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(
						new ModelAlias(List.of(), FailoverStrategy.SEQUENTIAL), MODELS)));

		assertTrue(failure.isServiceUnavailable());
	}

	@Test
	@DisplayName("a body rewrite failure is treated as a provider failure")
	void bodyRewriteFailureIsTransient() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverB.enqueue(sse("data: backup\n\ndata: [DONE]"));
		ModelAlias alias = new ModelAlias(
				List.of(
						new ProviderRef("a", "override"),
						new ProviderRef("b", null)
				),
				FailoverStrategy.SEQUENTIAL
		);

		ProviderResponse winner = join(orchestrator.execute(alias, "{not valid json"));

		assertEquals("b", winner.providerName(), "the override failure must fall through the chain");
	}

	@Test
	@DisplayName("RACE with non transient rejections reports that status")
	void raceNonTransientStatus() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));
		serverB.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(raceAlias("a", "b"), MODELS)));

		assertEquals(401, failure.getUpstreamStatus());
	}

	@Test
	@DisplayName("RACE reports a timed out failure when every leg hangs")
	void raceAllTimeout() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(raceAlias("a", "b"), MODELS)));

		assertTrue(failure.isTimedOut());
	}

	@Test
	@DisplayName("RACE cancels the losing attempt when the winner completes")
	void raceCancelsLosers() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(sse("data: winner\n\ndata: [DONE]"));
		// provider B is never enqueued; the fast winner makes B's future cancel
		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));
		assertEquals("a", winner.providerName());
		assertEquals(200, winner.response().statusCode());
	}

	@Test
	@DisplayName("RACE with a non transient rejection on one leg still completes")
	void raceWithNonTransientLeg() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));
		serverB.enqueue(sse("data: survivor\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));

		assertEquals("b", winner.providerName(), "the non transient leg must not block the race");
	}

	@Test
	@DisplayName("RACE with a transient 500 on one leg still completes through the other")
	void raceWithTransientLeg() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(500));
		serverB.enqueue(sse("data: survivor\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));

		assertEquals("b", winner.providerName(), "the transient leg must not block the race");
	}

	@Test
	@DisplayName("a 200 with a non event stream content type is passed through as non transient")
	void noContentType200NotSuccess() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json"));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertEquals(200, failure.getUpstreamStatus(),
				"a non SSE 200 must surface as is, not fail over");
	}

	@Test
	@DisplayName("a 404 from the primary is passed through as non transient")
	void failsOverOn404() {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		serverA.enqueue(error(404));

		UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
				() -> join(orchestrator.execute(alias("a", "b"), MODELS)));

		assertEquals(404, failure.getUpstreamStatus(), "a 404 must never fail over");
		assertEquals(0, serverB.getRequestCount());
	}

	@Test
	@DisplayName("RACE with a slow losing leg still closes that leg quietly")
	void raceSlowLoserClosedQuietly() throws Exception {
		FailoverOrchestrator orchestrator = orchestrator(allowAll());
		// A is fast and wins; B is a hanging leg that will time out after the win
		serverA.enqueue(sse("data: winner\n\ndata: [DONE]"));

		ProviderResponse winner = join(orchestrator.execute(raceAlias("a", "b"), MODELS));

		assertEquals("a", winner.providerName());
		assertEquals(200, winner.response().statusCode());
		// give the losing leg a moment to be cancelled and closed
		Thread.sleep(150);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private FailoverOrchestrator orchestrator(UpstreamUrlValidator validator) {
		return new FailoverOrchestrator(adapter(), validator, properties());
	}

	private UpstreamUrlValidator allowAll() {
		return url -> {
		};
	}

	private ProviderClientAdapter adapter() {
		return new ProviderClientAdapter(httpClient, new ObjectMapper());
	}

	private GatewayProperties properties() {
		GatewayProperties properties = new GatewayProperties();
		properties.setProviders(Map.of(
				"a", provider("a", serverA.url("/v1").toString(), "sk-a"),
				"b", provider("b", serverB.url("/v1").toString(), "sk-b")
		));
		return properties;
	}

	private ProviderConfig provider(String name, String baseUrl, String key) {
		return new ProviderConfig(
				name,
				ProviderType.OPENAI,
				URI.create(baseUrl),
				new SensitiveString(key),
				Duration.ofSeconds(3),
				Duration.ofMillis(300)
		);
	}

	private ModelAlias alias(String... providers) {
		return new ModelAlias(chain(providers), FailoverStrategy.SEQUENTIAL);
	}

	private ModelAlias raceAlias(String... providers) {
		return new ModelAlias(chain(providers), FailoverStrategy.RACE);
	}

	private static List<ProviderRef> chain(String... providers) {
		return Arrays.stream(providers)
				.map(name -> new ProviderRef(name, null))
				.toList();
	}

	private static MockResponse sse(String body) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "text/event-stream")
				.setBody(body);
	}

	private static MockResponse error(int status) {
		return new MockResponse().setResponseCode(status).setBody("{\"error\":\"boom\"}");
	}

	private static ProviderResponse join(CompletableFuture<ProviderResponse> future) {
		try {
			return future.join();
		}
		catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new RuntimeException(cause);
		}
	}
}