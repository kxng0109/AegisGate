package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.ModelPriceCatalog;
import io.github.kxng0109.aegisgate.ledger.ModelPricingRepository;
import io.github.kxng0109.aegisgate.proxy.ProxyController;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import io.github.kxng0109.aegisgate.proxy.protocol.*;
import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.github.kxng0109.aegisgate.security.filter.CachedBodyHttpServletRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("E12 Coverage Support")
@SuppressWarnings("DataFlowIssue")
class E12CoverageSupportTest {

	private static final HttpResponse.ResponseInfo INFO = new HttpResponse.ResponseInfo() {
		@Override
		public int statusCode() {
			return 200;
		}

		@Override
		public HttpHeaders headers() {
			return HttpHeaders.of(Map.of(), (k, v) -> true);
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_2;
		}
	};

	@Test
	@DisplayName("LineTooLongException getters return correct values")
	void testLineTooLongExceptionAccessors() {
		LineTooLongException ex = new LineTooLongException(1024, 2048, "openai");
		assertThat(ex.limitBytes()).isEqualTo(1024);
		assertThat(ex.actualBytes()).isEqualTo(2048);
		assertThat(ex.provider()).isEqualTo("openai");
	}

	@Test
	@DisplayName("DefaultSseLineGuardFactory creates guards and body handlers correctly")
	void testDefaultSseLineGuardFactory() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true,
				16384,
				10,
				SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(SseLineGuard.ProviderType.ANTHROPIC, new SseLineGuard.ProviderConfig(32768, 1000, 1048576)),
				Duration.ofSeconds(30),
				Duration.ofSeconds(5)
		);
		DefaultSseLineGuardFactory factory = new DefaultSseLineGuardFactory(
				props,
				new SimpleMeterRegistry(),
				new ObjectMapper()
		);

		assertThat(factory.properties()).isEqualTo(props);

		// With null requestId
		DefaultSseLineGuard guard1 = factory.newGuard(SseLineGuard.ProviderType.OPENAI, "openai", null);
		assertThat(guard1).isNotNull();

		// With non-null requestId and configured provider
		UUID reqId = UUID.randomUUID();
		DefaultSseLineGuard guard2 = factory.newGuard(SseLineGuard.ProviderType.ANTHROPIC, "anthropic", reqId);
		assertThat(guard2).isNotNull();

		// Body handlers
		BoundedLineBodyHandler defaultHandler = factory.bodyHandlerForProvider(SseLineGuard.ProviderType.OPENAI);
		assertThat(defaultHandler.maxLineBytes()).isEqualTo(18022); // 16384 * 1.1

		BoundedLineBodyHandler anthropicHandler = factory.bodyHandlerForProvider(SseLineGuard.ProviderType.ANTHROPIC);
		assertThat(anthropicHandler.maxLineBytes()).isEqualTo(36044); // 32768 * 1.1

		// Update properties
		SseLineGuardProperties updated = new SseLineGuardProperties(
				false,
				8192,
				0,
				SseLineGuard.Action.REJECT_LINE_CONTINUE,
				Map.of(),
				Duration.ofSeconds(10),
				Duration.ofSeconds(2)
		);
		factory.updateProperties(updated);
		assertThat(factory.properties()).isEqualTo(updated);
	}

	@Test
	@DisplayName("SseLineGuardAutoConfig beans and config reloader")
	void testSseLineGuardAutoConfigAndReloader() {
		SseLineGuardAutoConfig config = new SseLineGuardAutoConfig();
		SseLineGuardProperties props = SseLineGuardProperties.DEFAULTS;
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ObjectMapper mapper = new ObjectMapper();

		SseLineGuardAutoConfig.SseLineGuardFactory factory = config.sseLineGuardFactory(props, registry, mapper);
		assertThat(factory).isNotNull();

		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		MockEnvironment env = new MockEnvironment();
		SseLineGuardAutoConfig.SseLineGuardConfigReloader reloader = config.sseLineGuardConfigReloader(
				env,
				validator,
				factory
		);
		assertThat(reloader).isNotNull();

		// Reload without changes
		reloader.reload();

		// Reload with property changes
		env.setProperty("aegisgate.sse.line-guard.global-default-bytes", "4096");
		reloader.reload();
		assertThat(factory.properties().globalDefaultBytes()).isEqualTo(4096);

		// Reload with binding failure (should log warning and keep old)
		env.setProperty("aegisgate.sse.line-guard.global-default-bytes", "-1");
		reloader.reload();
		assertThat(factory.properties().globalDefaultBytes()).isEqualTo(4096);

		// Reload with validation constraint violation (e.g. safety-margin > 100)
		env.setProperty("aegisgate.sse.line-guard.global-default-bytes", "2048");
		env.setProperty("aegisgate.sse.line-guard.safety-margin-percent", "150");
		reloader.reload();
		assertThat(factory.properties().globalDefaultBytes()).isEqualTo(4096); // unchanged due to validation error

		// Test reloader with a non-default factory implementation
		SseLineGuardAutoConfig.SseLineGuardFactory mockFactory = mock(SseLineGuardAutoConfig.SseLineGuardFactory.class);
		when(mockFactory.properties()).thenReturn(props);
		SseLineGuardAutoConfig.SseLineGuardConfigReloader reloader2 = config.sseLineGuardConfigReloader(
				env,
				validator,
				mockFactory
		);
		reloader2.reload();
	}

	@Test
	@DisplayName("BoundedLineBodyHandler spliterator properties and error branches")
	void testBoundedLineBodyHandlerSpliteratorBranches() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(100, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);

		MockSub mock = new MockSub();
		sub.onSubscribe(mock);

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().join();
		Spliterator<String> spliterator = stream.spliterator();
		assertThat(spliterator.trySplit()).isNull();
		assertThat(spliterator.estimateSize()).isEqualTo(Long.MAX_VALUE);
		assertThat(spliterator.characteristics()).isEqualTo(Spliterator.ORDERED | Spliterator.NONNULL);

		// Test RuntimeException propagation through subscriber
		BoundedLineBodyHandler handler2 = new BoundedLineBodyHandler(100, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub2 = handler2.apply(INFO);
		sub2.onSubscribe(new MockSub());
		sub2.onError(new IllegalStateException("runtime fail"));
		java.util.stream.Stream<String> stream2 = sub2.getBody().toCompletableFuture().join();
		assertThatThrownBy(stream2::toList)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("runtime fail");
	}

	@Test
	@DisplayName("BoundedLineBodyHandler CR followed by non-LF byte")
	void testCrFollowedByNonLf() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		sub.onSubscribe(new MockSub());

		sub.onNext(List.of(ByteBuffer.wrap("hello\rworld\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().join();
		assertThat(stream.toList()).containsExactly("hello", "world");
	}

	@Test
	@DisplayName("BoundedLineBodyHandler line length limit hit right at newline")
	void testLineLimitHitAtNewline() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(3, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		sub.onSubscribe(new MockSub());

		// 4 bytes then newline: lineLen is 4 when \n is hit
		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcd\n".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().join().toList();
		}).isInstanceOf(LineTooLongException.class);
	}

	@Test
	@DisplayName("BoundedLineBodyHandler line length limit hit right at CR")
	void testLineLimitHitAtCr() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(3, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		sub.onSubscribe(new MockSub());

		// 4 bytes then CR: lineLen is 4 when \r is hit
		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcd\r".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().join().toList();
		}).isInstanceOf(LineTooLongException.class);
	}

	@Test
	@DisplayName("DefaultSseLineGuard with null providerType and JSON serialization fallback")
	void testDefaultSseLineGuardEdgeBranches() throws Exception {
		ObjectMapper throwingMapper = mock(ObjectMapper.class);
		when(throwingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
				.thenThrow(new JacksonException("mock fail") {
				});

		SseLineGuardProperties props = SseLineGuardProperties.DEFAULTS;
		DefaultSseLineGuard guard = new DefaultSseLineGuard(
				props,
				new SimpleMeterRegistry(),
				throwingMapper,
				null, // null providerType falls back to UNKNOWN
				null, // null providerName falls back to "unknown"
				null  // null requestId falls back to UUID.randomUUID()
		);

		// Line too long triggers fallback in buildErrorJson
		List<String> errorLines = guard.checkLine("x".repeat(20000), null);
		assertThat(errorLines).hasSize(3);
		assertThat(errorLines.get(1)).contains("SSE line limit exceeded");
	}

	@Test
	@DisplayName("DefaultSseLineGuard rate limit with REJECT_LINE_CONTINUE")
	void testDefaultSseLineGuardRateLimitContinue() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true,
				1024,
				10,
				SseLineGuard.Action.REJECT_LINE_CONTINUE,
				Map.of(SseLineGuard.ProviderType.OPENAI, new SseLineGuard.ProviderConfig(16384, 1, 10)),
				Duration.ofSeconds(30),
				Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = new DefaultSseLineGuard(
				props,
				new SimpleMeterRegistry(),
				new ObjectMapper(),
				SseLineGuard.ProviderType.OPENAI,
				"openai",
				UUID.randomUUID()
		);

		// First line passes
		List<String> first = guard.checkLine("hello", SseLineGuard.ProviderType.OPENAI);
		assertThat(first).containsExactly("hello");

		// Second line exceeds line rate limit and REJECT_LINE_CONTINUE returns empty list
		List<String> second = guard.checkLine("world", SseLineGuard.ProviderType.OPENAI);
		assertThat(second).isEmpty();
		assertThat(guard.isRejected()).isFalse();

		// Third line exceeds byte rate limit and returns empty list
		List<String> third = guard.checkLine("a".repeat(50), SseLineGuard.ProviderType.OPENAI);
		assertThat(third).isEmpty();
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("SseLineGuard.ProviderType from contract mappings")
	void testProviderTypeMappings() {
		assertThat(SseLineGuard.ProviderType.from(null)).isEqualTo(SseLineGuard.ProviderType.UNKNOWN);
		assertThat(SseLineGuard.ProviderType.from(ProviderType.OPENAI)).isEqualTo(
				SseLineGuard.ProviderType.OPENAI);
		assertThat(SseLineGuard.ProviderType.from(ProviderType.ANTHROPIC)).isEqualTo(
				SseLineGuard.ProviderType.ANTHROPIC);
		assertThat(SseLineGuard.ProviderType.from(ProviderType.OLLAMA)).isEqualTo(
				SseLineGuard.ProviderType.OLLAMA);
	}

	@Test
	@DisplayName("ProxyController extractModel and request error edge cases")
	void testProxyControllerEdgeBranches() throws Exception {
		FailoverOrchestrator orchestrator = mock(FailoverOrchestrator.class);
		GatewayProperties props = new GatewayProperties();
		ModelAlias alias = new ModelAlias(
				List.of(new ProviderRef("openai-p", null)),
				FailoverStrategy.SEQUENTIAL
		);
		props.getAliases().put("test-model", alias);
		props.getProviders().put(
				"openai-p", new ProviderConfig(
						"openai-p",
						ProviderType.OPENAI,
						URI.create("https://api.openai.com"),
						new SensitiveString("sk-test"),
						Duration.ofSeconds(5),
						Duration.ofSeconds(30)
				)
		);

		CostCalculator costCalc = mock(CostCalculator.class);
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		SseFlushStrategy flush = mock(SseFlushStrategy.class);
		DefaultSseLineGuardFactory lineGuardFactory = new DefaultSseLineGuardFactory(
				SseLineGuardProperties.DEFAULTS,
				new SimpleMeterRegistry(),
				new ObjectMapper()
		);
		ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
				new OpenAiPassthroughAdapter(new ObjectMapper()),
				new AnthropicAdapter(new ObjectMapper()),
				new GeminiAdapter(new ObjectMapper()),
				new DeepSeekAdapter(new ObjectMapper()),
				new OllamaAdapter(new ObjectMapper())
		);

		ProxyController controller = new ProxyController(
				orchestrator,
				props,
				new ObjectMapper(),
				resolver,
				costCalc,
				publisher,
				flush,
				lineGuardFactory
		);

		MockHttpServletRequest req = new MockHttpServletRequest();

		// Array body instead of object
		var res1 = controller.proxyChatCompletions("[1, 2, 3]", req);
		assertThat(res1.getStatusCode().value()).isEqualTo(400);

		// Object without string model (number instead)
		var res2 = controller.proxyChatCompletions("{\"model\": 123}", req);
		assertThat(res2.getStatusCode().value()).isEqualTo(400);

		// CompletionException with generic RuntimeException
		when(orchestrator.execute(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
				new java.util.concurrent.CompletionException(new IllegalStateException("simulated unexpected boom"))
		));
		assertThatThrownBy(() -> controller.proxyChatCompletions("{\"model\": \"test-model\"}", req))
				.isInstanceOf(UpstreamUnavailableException.class)
				.hasMessageContaining("upstream request failed unexpectedly");

		// Non-200 upstream response relaying raw body with client disconnect
		HttpResponse<java.util.stream.Stream<String>> errHttpResp = mock(HttpResponse.class);
		when(errHttpResp.statusCode()).thenReturn(500);
		when(errHttpResp.body()).thenReturn(java.util.stream.Stream.of("error line 1", "error line 2"));
		when(orchestrator.execute(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
				new ProviderResponse("openai-p", errHttpResp)
		));

		var res3 = controller.proxyChatCompletions("{\"model\": \"test-model\"}", req);
		assertThat(res3.getStatusCode().value()).isEqualTo(500);
		// Simulate client disconnect during error body write
		res3.getBody().writeTo(new java.io.OutputStream() {
			@Override
			public void write(int b) throws java.io.IOException {
				throw new java.io.IOException("client gone");
			}
		});

		// 200 upstream response with SseConnectionLimitException in flush strategy
		when(flush.register(any())).thenThrow(new SseConnectionLimitException("limit reached"));
		HttpResponse<java.util.stream.Stream<String>> okHttpResp = mock(HttpResponse.class);
		when(okHttpResp.statusCode()).thenReturn(200);
		when(okHttpResp.body()).thenReturn(java.util.stream.Stream.of(
				"data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
				"data: [DONE]"
		));
		when(orchestrator.execute(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
				new ProviderResponse("openai-p", okHttpResp)
		));

		var res4 = controller.proxyChatCompletions("{\"model\": \"test-model\"}", req);
		assertThat(res4.getStatusCode().value()).isEqualTo(200);
		// Writing to servlet output stream triggers connection limit branch and cleanly returns
		res4.getBody()
		    .writeTo(new RecordingServletOutputStream());

		// 200 upstream response with LineTooLongException thrown during stream iteration
		doReturn(null).when(flush).register(any());
		HttpResponse<Stream<String>> oomHttpResp = mock(HttpResponse.class);
		when(oomHttpResp.statusCode()).thenReturn(200);
		Stream<String> throwingStream = Stream.generate(() -> {
			throw new LineTooLongException(100, 200, "openai-p");
		});
		when(oomHttpResp.body()).thenReturn(throwingStream);
		when(orchestrator.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(
				new ProviderResponse("openai-p", oomHttpResp)
		));

		var res5 = controller.proxyChatCompletions("{\"model\": \"test-model\"}", req);
		ByteArrayOutputStream out5 = new ByteArrayOutputStream();
		res5.getBody().writeTo(out5);
		assertThat(out5.toString(StandardCharsets.UTF_8)).contains("event: error").contains("LINE_TOO_LONG");
	}

	@Test
	@DisplayName("CachedBodyHttpServletRequest stream accessors and SHA256Hash equals branches")
	void testCachedBodyAndHashEdgeCases() throws Exception {
		MockHttpServletRequest mockReq = new MockHttpServletRequest();
		mockReq.setContent("test content".getBytes(StandardCharsets.UTF_8));
		CachedBodyHttpServletRequest wrapped =
				new CachedBodyHttpServletRequest(mockReq, 1024);

		var stream = wrapped.getInputStream();
		assertThat(stream.isReady()).isTrue();
		assertThat(stream.isFinished()).isFalse();
		assertThat(stream.read()).isEqualTo('t');
		assertThatThrownBy(() -> stream.setReadListener(null))
				.isInstanceOf(UnsupportedOperationException.class);

		SHA256Hash h1 = SHA256Hash.fromRawKey("gw-key1");
		SHA256Hash h2 = SHA256Hash.fromRawKey("gw-key2");
		Object foreignObj = "string";
		assertThat(h1.equals(null)).isFalse();
		assertThat(h1.equals(foreignObj)).isFalse();
		assertThat(h1.equals(h2)).isFalse();
		assertThat(h1.equals(h1)).isTrue();
		assertThat(h1.hashCode()).isEqualTo(h1.hashCode());
		assertThat(h1.toString()).isEqualTo("****");
	}

	@Test
	@DisplayName("AdaptiveSseFlushStrategy metrics and ModelPriceCatalog lookup branches")
	void testAdaptiveSseFlushAndCatalogBranches() throws Exception {
		SseFlushProperties props = new SseFlushProperties(16, 100, 500, 65536, 1000, true);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(props, registry);

		assertThat(strategy.registeredConnectionCount()).isEqualTo(0);
		assertThat(strategy.activeWatchdogCount()).isEqualTo(0);
		assertThat(strategy.backpressureActiveConnections()).isEqualTo(0);
		assertThat(strategy.bufferedBytes()).isEqualTo(0);
		assertThat(strategy.maxFlushLagMs()).isGreaterThanOrEqualTo(0);

		// onWrite with unregistered stream returns false
		TestServletOutputStreams.RecordingServletOutputStream unregistered = new TestServletOutputStreams.RecordingServletOutputStream();
		assertThat(strategy.onWrite(unregistered, 10)).isFalse();

		// unregister unknown handle
		strategy.unregister(new SseFlushStrategy.FlushHandle() {
			@Override
			public long connectionId() {
				return 999999L;
			}

			@Override
			public jakarta.servlet.ServletOutputStream outputStream() {
				return unregistered;
			}
		});

		strategy.close();

		// ModelPriceCatalog lookup null and blank
		ModelPricingRepository repo = mock(ModelPricingRepository.class);
		ModelPriceCatalog catalog = new ModelPriceCatalog(repo);
		assertThat(catalog.lookup(ProviderType.OPENAI, null)).isEmpty();
		assertThat(catalog.lookup(ProviderType.OPENAI, "   ")).isEmpty();
		catalog.invalidate();
	}

	private static final class MockSub implements Flow.Subscription {
		final AtomicLong requested = new AtomicLong();
		final AtomicInteger cancelled = new AtomicInteger();

		@Override
		public void request(long n) {
			requested.addAndGet(n);
		}

		@Override
		public void cancel() {
			cancelled.incrementAndGet();
		}
	}
}