package io.github.kxng0109.aegisgate.proxy.protocol;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.proxy.failover.*;
import io.github.kxng0109.aegisgate.proxy.sse.*;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration style tests that run the real failover pipeline against mock Anthropic, Ollama, Gemini, and DeepSeek servers:
 * the adapter translates the request, the orchestrator accepts each native streaming content type, and the normalizer
 * rewrites the stream into OpenAI shaped SSE lines.
 */
@DisplayName("ProtocolNormalizationIntegration")
@SuppressWarnings("DataFlowIssue")
class ProtocolNormalizationIntegrationTest {

	private static final String BODY = "{\"model\":\"claude-sonnet-5\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
	private static final String GEMINI_BODY = "{\"model\":\"gemini-2.5-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
	private static final String DEEPSEEK_BODY = "{\"model\":\"deepseek-reasoner\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"reasoning_effort\":\"high\"}";

	private MockWebServer anthropicServer;
	private MockWebServer ollamaServer;
	private MockWebServer geminiServer;
	private MockWebServer deepseekServer;
	private HttpClient httpClient;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() throws Exception {
		anthropicServer = new MockWebServer();
		anthropicServer.start();
		ollamaServer = new MockWebServer();
		ollamaServer.start();
		geminiServer = new MockWebServer();
		geminiServer.start();
		deepseekServer = new MockWebServer();
		deepseekServer.start();
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
		geminiServer.shutdown();
		deepseekServer.shutdown();
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
	@DisplayName("an Anthropic multi-turn tool use stream is normalized to OpenAI tool_calls chunks")
	void anthropicToolCallingStreamNormalized() {
		String toolCallStream = """
				event: message_start
				data: {"type":"message_start","message":{"id":"msg_tool","type":"message","role":"assistant","content":[],"model":"claude-3-7-sonnet","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":30,"output_tokens":1}}}
				event: content_block_start
				data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01ABC","name":"get_stock_quote"}}
				event: content_block_delta
				data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"symbol\\": \\"AAPL\\"}"}}
				event: content_block_stop
				data: {"type":"content_block_stop","index":0}
				event: message_delta
				data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":25}}
				event: message_stop
				data: {"type":"message_stop"}
				""";
		anthropicServer.enqueue(new MockResponse().setResponseCode(200)
		                                          .addHeader("Content-Type", "text/event-stream")
		                                          .setBody(toolCallStream));

		String toolReqBody = """
				{
				  "model": "claude-3-7-sonnet",
				  "messages": [{"role": "user", "content": "What is AAPL trading at?"}],
				  "tools": [{
				    "type": "function",
				    "function": {
				      "name": "get_stock_quote",
				      "description": "Fetch live stock quote",
				      "parameters": {"type": "object", "properties": {"symbol": {"type": "string"}}, "required": ["symbol"]}
				    }
				  }]
				}""";

		ProviderResponse winner = join(orchestrator("anthropic").execute(alias("anthropic"), toolReqBody));
		assertEquals(200, winner.response().statusCode());

		List<String> lines = relay(winner, new AnthropicAdapter(objectMapper).newNormalizer(true, "claude-3-7-sonnet"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"name\":\"get_stock_quote\"")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("AAPL")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"tool_calls\"")));
		assertEquals("data: [DONE]", lines.getLast());
	}

	@Test
	@DisplayName("a Gemini stream with thoughts and functionCall is normalized to OpenAI chunks")
	void geminiStreamNormalized() {
		geminiServer.enqueue(new MockResponse().setResponseCode(200)
		                                       .addHeader("Content-Type", "text/event-stream")
		                                       .setBody(geminiStream()));

		ProviderResponse winner = join(orchestrator("gemini").execute(alias("gemini"), GEMINI_BODY));

		assertEquals(200, winner.response().statusCode());
		List<String> lines = relay(winner, new GeminiAdapter(objectMapper).newNormalizer(true, "gemini-2.5-flash"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"reasoning_content\":\"Analyzing question\"")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"delta\":{\"content\":\"Hello world\"}")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"stop\"")));
		assertEquals("data: [DONE]", lines.getLast());
	}

	@Test
	@DisplayName("a Gemini functionCall tool use stream is normalized to OpenAI tool_calls chunks")
	void geminiToolCallingStreamNormalized() {
		String geminiToolStream = """
				data: {"candidates": [{"content": {"parts": [{"functionCall": {"name": "lookup_cve", "args": {"cve_id": "CVE-2024-38816"}}}], "role": "model"}, "finishReason": "STOP", "index": 0}], "usageMetadata": {"promptTokenCount": 50, "candidatesTokenCount": 20, "totalTokenCount": 70}}
				""";
		geminiServer.enqueue(new MockResponse().setResponseCode(200)
		                                       .addHeader("Content-Type", "text/event-stream")
		                                       .setBody(geminiToolStream));

		String geminiToolReq = """
				{
				  "model": "gemini-2.5-flash",
				  "messages": [{"role": "user", "content": "Look up CVE-2024-38816"}],
				  "tools": [{
				    "type": "function",
				    "function": {
				      "name": "lookup_cve",
				      "parameters": {"type": "object", "properties": {"cve_id": {"type": "string"}}, "required": ["cve_id"]}
				    }
				  }]
				}""";

		ProviderResponse winner = join(orchestrator("gemini").execute(alias("gemini"), geminiToolReq));
		assertEquals(200, winner.response().statusCode());

		List<String> lines = relay(winner, new GeminiAdapter(objectMapper).newNormalizer(true, "gemini-2.5-flash"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"name\":\"lookup_cve\"")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("CVE-2024-38816")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"tool_calls\"")));
		assertEquals("data: [DONE]", lines.getLast());
	}

	@Test
	@DisplayName("a DeepSeek stream with reasoning and prompt cache hits is normalized to OpenAI chunks")
	void deepseekStreamNormalized() {
		deepseekServer.enqueue(new MockResponse().setResponseCode(200)
		                                         .addHeader("Content-Type", "text/event-stream")
		                                         .setBody(deepseekStream()));

		ProviderResponse winner = join(orchestrator("deepseek").execute(alias("deepseek"), DEEPSEEK_BODY));

		assertEquals(200, winner.response().statusCode());
		DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "deepseek-reasoner", true);
		List<String> lines = relay(winner, normalizer);
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"reasoning_content\":\"Step by step reasoning\"")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"content\":\"Solution found\"")));
		assertEquals("data: [DONE]", lines.getLast());
		assertEquals(64L, normalizer.cachedTokens());
	}

	@Test
	@DisplayName("a DeepSeek tool calling stream is normalized to OpenAI tool_calls chunks")
	void deepseekToolCallingStreamNormalized() {
		String deepseekToolStream = """
				data: {"id":"ds-tool-1","choices":[{"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_01","type":"function","function":{"name":"query_db","arguments":""}}]}}],"model":"deepseek-chat"}
				data: {"id":"ds-tool-1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"query\\": \\"SELECT 1\\"}"}}]}}]}
				data: {"id":"ds-tool-1","choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":40,"prompt_cache_hit_tokens":32,"prompt_cache_miss_tokens":8,"completion_tokens":15,"total_tokens":55}}
				data: [DONE]
				""";
		deepseekServer.enqueue(new MockResponse().setResponseCode(200)
		                                         .addHeader("Content-Type", "text/event-stream")
		                                         .setBody(deepseekToolStream));

		String deepseekToolReq = """
				{
				  "model": "deepseek-chat",
				  "messages": [{"role": "user", "content": "Execute SELECT 1"}],
				  "tools": [{
				    "type": "function",
				    "function": {
				      "name": "query_db",
				      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}
				    }
				  }]
				}""";

		ProviderResponse winner = join(orchestrator("deepseek").execute(alias("deepseek"), deepseekToolReq));
		assertEquals(200, winner.response().statusCode());

		DeepSeekSseNormalizer normalizer = new DeepSeekSseNormalizer(objectMapper, "deepseek-chat", true);
		List<String> lines = relay(winner, normalizer);
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"name\":\"query_db\"")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("SELECT 1")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("\"finish_reason\":\"tool_calls\"")));
		assertEquals("data: [DONE]", lines.getLast());
		assertEquals(32L, normalizer.cachedTokens());
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
			case "gemini" -> new ProviderConfig(
					"gemini", ProviderType.GEMINI,
					URI.create(geminiServer.url("/").toString()),
					new SensitiveString("gemini-test-key"), Duration.ofSeconds(3), Duration.ofSeconds(5)
			);
			case "deepseek" -> new ProviderConfig(
					"deepseek", ProviderType.DEEPSEEK,
					URI.create(deepseekServer.url("/").toString()),
					new SensitiveString("sk-deepseek-key"), Duration.ofSeconds(3), Duration.ofSeconds(5)
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
				new GeminiAdapter(objectMapper),
				new DeepSeekAdapter(objectMapper),
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

	private static SseLineGuardFactory testLineGuardFactory() {
		MeterRegistry registry = new SimpleMeterRegistry();
		ObjectMapper mapper = new ObjectMapper();
		SseLineGuardProperties props = SseLineGuardProperties.DEFAULTS;
		SseLineGuardFactory base = new DefaultSseLineGuardFactory(props, registry, mapper);
		return new SseLineGuardFactory() {
			@Override
			public DefaultSseLineGuard newGuard(
					SseLineGuard.ProviderType t, String n, UUID id
			) {
				return base.newGuard(t, n, id);
			}

			@Override
			public BoundedLineBodyHandler bodyHandlerForProvider(
					SseLineGuard.ProviderType t
			) {
				return base.bodyHandlerForProvider(t);
			}

			@Override
			public SseLineGuardProperties properties() {
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

	private static String geminiStream() {
		return """
				data: {"candidates": [{"content": {"parts": [{"thought": true, "text": "Analyzing question"}], "role": "model"}, "finishReason": null, "index": 0}], "modelVersion": "gemini-2.5-flash"}
				data: {"candidates": [{"content": {"parts": [{"text": "Hello world"}], "role": "model"}, "finishReason": "STOP", "index": 0}], "usageMetadata": {"promptTokenCount": 20, "candidatesTokenCount": 10, "totalTokenCount": 30}}
				""";
	}

	private static String deepseekStream() {
		return """
				data: {"id":"ds-1","choices":[{"delta":{"reasoning_content":"Step by step reasoning"}}],"model":"deepseek-reasoner"}
				data: {"id":"ds-1","choices":[{"delta":{"content":"Solution found"}}]}
				data: {"id":"ds-1","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":32,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":0,"completion_tokens":10,"total_tokens":42}}
				data: [DONE]
				""";
	}

	private static String ollamaStream() {
		return """
				{"message":{"role":"assistant","content":"Hello"},"done":false}
				{"message":{"role":"assistant","content":""},"done":true,"total_duration":1000}
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
