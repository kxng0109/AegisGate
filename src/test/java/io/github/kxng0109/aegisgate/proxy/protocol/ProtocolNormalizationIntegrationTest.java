package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.proxy.failover.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration style tests that run the real failover pipeline against mock Anthropic and Ollama servers: the adapter
 * translates the request, the orchestrator accepts each native streaming content type, and the normalizer rewrites the
 * stream into OpenAI shaped SSE lines.
 */
@DisplayName("Protocol normalization through the orchestrator")
class ProtocolNormalizationIntegrationTest {

	private static final String BODY = "{\"model\":\"claude-sonnet-5\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";

	private MockWebServer anthropicServer;
	private MockWebServer ollamaServer;
	private HttpClient httpClient;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() throws Exception {
		anthropicServer = new MockWebServer();
		anthropicServer.start();
		ollamaServer = new MockWebServer();
		ollamaServer.start();
		httpClient = HttpClient.newBuilder()
		                       .version(HttpClient.Version.HTTP_2)
		                       .connectTimeout(Duration.ofSeconds(5))
		                       .executor(Executors.newVirtualThreadPerTaskExecutor())
		                       .followRedirects(HttpClient.Redirect.NEVER)
		                       .build();
		objectMapper = new ObjectMapper();
	}

	@AfterEach
	void tearDown() throws Exception {
		anthropicServer.shutdown();
		ollamaServer.shutdown();
	}

	@Test
	@DisplayName("an Anthropic stream is normalized to OpenAI chunks")
	void anthropicStreamNormalized() {
		anthropicServer.enqueue(new MockResponse().setResponseCode(200)
		                                          .addHeader("Content-Type", "text/event-stream")
		                                          .setBody(anthropicStream()));

		ProviderResponse winner = join(orchestrator("anthropic").execute(alias("anthropic"), BODY));

		assertEquals(200, winner.response().statusCode());
		List<String> lines = relay(winner, new AnthropicAdapter(objectMapper).newNormalizer(false, "claude-sonnet-5"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"delta\":{\"content\":\"Hello\"}")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"stop\"")));
		assertEquals("data: [DONE]", lines.getLast());
	}

	@Test
	@DisplayName("an Ollama NDJSON stream is normalized to OpenAI chunks")
	void ollamaStreamNormalized() {
		ollamaServer.enqueue(new MockResponse().setResponseCode(200)
		                                       .addHeader("Content-Type", "application/x-ndjson")
		                                       .setBody(ollamaStream()));

		ProviderResponse winner = join(orchestrator("ollama").execute(alias("ollama"), ollamaBody()));

		assertEquals(200, winner.response().statusCode());
		List<String> lines = relay(winner, new OllamaAdapter(objectMapper).newNormalizer(false, "llama3.2"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"delta\":{\"content\":\"Hello\"}")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"stop\"")));
		assertEquals("data: [DONE]", lines.getLast());
	}

	@Test
	@DisplayName("the orchestrator accepts the Ollama NDJSON content type as a success")
	void ollamaContentTypeClassifiedAsSuccess() {
		ollamaServer.enqueue(new MockResponse().setResponseCode(200)
		                                       .addHeader("Content-Type", "application/x-ndjson")
		                                       .setBody(ollamaStream()));

		ProviderResponse winner = join(orchestrator("ollama").execute(alias("ollama"), ollamaBody()));

		assertEquals("ollama", winner.providerName(), "NDJSON must be treated as a streaming success");
	}

	@Test
	@DisplayName("the Anthropic request carries the native path and headers")
	void anthropicRequestShape() throws InterruptedException {
		anthropicServer.enqueue(new MockResponse().setResponseCode(200)
		                                          .addHeader("Content-Type", "text/event-stream")
		                                          .setBody(anthropicStream()));

		join(orchestrator("anthropic").execute(alias("anthropic"), BODY));

		okhttp3.mockwebserver.RecordedRequest recorded = anthropicServer.takeRequest();
		assertEquals("/v1/messages", recorded.getPath());
		assertEquals("sk-ant", recorded.getHeader("x-api-key"));
		assertEquals(AnthropicAdapter.ANTHROPIC_VERSION, recorded.getHeader("anthropic-version"));
		JsonNode body = objectMapper.readTree(recorded.getBody().readUtf8());
		assertEquals("claude-sonnet-5", body.get("model").asString());
		assertTrue(body.get("stream").asBoolean());
		assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, body.get("max_tokens").asInt());
	}

	@Test
	@DisplayName("the Ollama request carries the native path and options")
	void ollamaRequestShape() throws InterruptedException {
		ollamaServer.enqueue(new MockResponse().setResponseCode(200)
		                                       .addHeader("Content-Type", "application/x-ndjson")
		                                       .setBody(ollamaStream()));

		join(orchestrator("ollama").execute(alias("ollama"), ollamaBody()));

		okhttp3.mockwebserver.RecordedRequest recorded = ollamaServer.takeRequest();
		assertEquals("/api/chat", recorded.getPath());
		assertNull(recorded.getHeader("Authorization"));
		JsonNode body = objectMapper.readTree(recorded.getBody().readUtf8());
		assertEquals("llama3.2", body.get("model").asString());
		assertTrue(body.get("stream").asBoolean());
		assertEquals(0.4, body.path("options").path("temperature").asDouble());
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private FailoverOrchestrator orchestrator(String providerName) {
		GatewayProperties properties = new GatewayProperties();
		ProviderConfig config = switch (providerName) {
			case "anthropic" -> new ProviderConfig(
					"anthropic", ProviderType.ANTHROPIC,
					URI.create(anthropicServer.url("/").toString()),
					new SensitiveString("sk-ant"), Duration.ofSeconds(3), Duration.ofSeconds(5)
			);
			case "ollama" -> new ProviderConfig(
					"ollama", ProviderType.OLLAMA,
					URI.create(ollamaServer.url("/").toString()),
					null, Duration.ofSeconds(3), Duration.ofSeconds(5)
			);
			default -> throw new IllegalArgumentException("unknown provider " + providerName);
		};
		properties.setProviders(Map.of(providerName, config));
		ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
				new OpenAiPassthroughAdapter(objectMapper),
				new AnthropicAdapter(objectMapper),
				new OllamaAdapter(objectMapper)
		);
		UpstreamUrlValidator allowAll = url -> {
		};
		return new FailoverOrchestrator(
				new ProviderClientAdapter(httpClient, resolver, testLineGuardFactory()),
				allowAll,
				properties,
				new InMemoryCircuitBreakerFactory(properties)
		);
	}

	private static io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory testLineGuardFactory() {
		io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		ObjectMapper mapper = new ObjectMapper();
		io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardProperties props =
				io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardProperties.DEFAULTS;
		io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory base =
				new io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuardFactory(props, registry, mapper);
		return new io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory() {
			@Override
			public io.github.kxng0109.aegisgate.proxy.sse.DefaultSseLineGuard newGuard(
					io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType t, String n, java.util.UUID id
			) {
				return base.newGuard(t, n, id);
			}

			@Override
			public io.github.kxng0109.aegisgate.proxy.sse.BoundedLineBodyHandler bodyHandlerForProvider(
					io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType t
			) {
				return base.bodyHandlerForProvider(t);
			}

			@Override
			public io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardProperties properties() {
				return props;
			}
		};
	}

	private static ModelAlias alias(String provider) {
		return new ModelAlias(List.of(new ProviderRef(provider, null)), FailoverStrategy.SEQUENTIAL);
	}

	private static String ollamaBody() {
		return "{\"model\":\"llama3.2\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"temperature\":0.4}";
	}

	private static String anthropicStream() {
		return """
				event: message_start
				data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":1}}}
				event: content_block_delta
				data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
				event: message_delta
				data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}
				event: message_stop
				data: {"type":"message_stop"}
				""";
	}

	private static String ollamaStream() {
		return """
				{"model":"llama3.2","created_at":"2023-08-04T08:52:19Z","message":{"role":"assistant","content":"Hello"},"done":false}
				{"model":"llama3.2","created_at":"2023-08-04T08:52:19Z","message":{"role":"assistant","content":""},"done":true,"total_duration":1,"load_duration":1,"prompt_eval_count":26,"eval_count":282,"eval_duration":1}
				""";
	}

	private static List<String> relay(ProviderResponse winner, SseNormalizer normalizer) {
		List<String> emitted = new ArrayList<>();
		try (Stream<String> lines = winner.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				emitted.addAll(normalizer.normalizeLine(line));
				if (normalizer.isDone()) {
					break;
				}
			}
		}
		return emitted;
	}

	private static ProviderResponse join(CompletableFuture<ProviderResponse> future) {
		try {
			return future.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new RuntimeException(cause);
		}
	}

	/**
	 * Redis free {@link CircuitBreakerFactory} for the integration tests: keeps one in memory breaker per provider so
	 * circuit state survives across requests without needing Redis.
	 */
	private static final class InMemoryCircuitBreakerFactory implements CircuitBreakerFactory {

		private final GatewayProperties gatewayProperties;
		private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

		private InMemoryCircuitBreakerFactory(GatewayProperties gatewayProperties) {
			this.gatewayProperties = gatewayProperties;
		}

		@Override
		public CircuitBreaker get(String providerName) {
			return breakers.computeIfAbsent(
					providerName, name -> new ProviderCircuitBreaker(name, Clock.systemUTC()));
		}

		@Override
		public Set<String> providerNames() {
			return gatewayProperties.getProviders().keySet();
		}

		@Override
		public void reset() {
			breakers.clear();
		}

		@Override
		public Map<String, CircuitBreaker.State> states() {
			return Map.of();
		}
	}
}