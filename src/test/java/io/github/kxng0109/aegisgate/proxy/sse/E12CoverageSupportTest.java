package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targeted tests for E12 helpers, factories, and exception accessors to satisfy JaCoCo branch coverage.
 */
@DisplayName("E12 Coverage Support")
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