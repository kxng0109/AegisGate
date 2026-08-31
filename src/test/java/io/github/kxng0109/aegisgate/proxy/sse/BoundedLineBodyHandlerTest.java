package io.github.kxng0109.aegisgate.proxy.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BoundedLineBodyHandler}.
 *
 * <p>These tests exercise the subscriber in isolation, without a real HTTP server.
 * A {@link MockSubscription} is used to track {@code request()} and {@code cancel()} calls on the upstream
 * publisher.</p>
 */
@DisplayName("BoundedLineBodyHandler")
@SuppressWarnings("DataFlowIssue")
class BoundedLineBodyHandlerTest {

	private static final HttpResponse.ResponseInfo INFO = new HttpResponse.ResponseInfo() {
		@Override
		public int statusCode() {
			return 200;
		}

		@Override
		public HttpHeaders headers() {
			Map<String, List<String>> empty = Collections.emptyMap();
			return HttpHeaders.of(empty, (k, v) -> true);
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_2;
		}
	};

	@Test
	@DisplayName("constructor rejects non-positive maxLineBytes")
	void constructorRejectsNonPositive() {
		assertThatThrownBy(() -> new BoundedLineBodyHandler(0, StandardCharsets.UTF_8))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedLineBodyHandler(-1, StandardCharsets.UTF_8))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("constructor uses UTF-8 when charset is null")
	void constructorDefaultsCharset() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(100, null);
		assertThat(handler.maxLineBytes()).isEqualTo(100);
	}

	@Test
	@DisplayName("simple lines terminated with newline are yielded in order")
	void simpleLines() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("hello\nworld\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		List<String> lines = stream.toList();
		assertThat(lines).containsExactly("hello", "world");
	}

	@Test
	@DisplayName("CRLF line endings are handled")
	void crlfLineEndings() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("foo\r\nbar\r\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("foo", "bar");
	}

	@Test
	@DisplayName("lone CR at EOF terminates a line")
	void loneCrAtEof() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("hello\r".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("hello");
	}

	@Test
	@DisplayName("a line exactly at the limit is allowed")
	void lineExactlyAtLimit() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(5, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("abcde\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("abcde");
	}

	@Test
	@DisplayName("a line one byte over the limit triggers LineTooLongException and cancels the upstream")
	void lineOneByteOverLimit() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(5, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcdef\n".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS).toList();
		}).isInstanceOf(LineTooLongException.class);

		assertThat(mock.cancelled.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("multi-buffer line is reassembled correctly")
	void multiBufferLine() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(20, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(
				ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)),
				ByteBuffer.wrap("def".getBytes(StandardCharsets.UTF_8)),
				ByteBuffer.wrap("ghi\n".getBytes(StandardCharsets.UTF_8))
		));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("abcdefghi");
	}

	@Test
	@DisplayName("line terminator split across buffers is handled")
	void terminatorSplitAcrossBuffers() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(20, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(
				ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)),
				ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8))
		));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("abc");
	}

	@Test
	@DisplayName("CRITICAL: 10 MB line without newline triggers OOM-safe rejection")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void oomPrevention() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(1024, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		// 10 MB is enough to prove the byte-level guard prevents OOM
		// (a 100 MB test was also validated but is too slow for CI).
		byte[] huge = new byte[10_000_000];
		Arrays.fill(huge, (byte) 'x');

		// Drive the producer in a separate thread so the publisher does not
		// block the test thread when the queue is full.
		Thread producer = new Thread(() -> {
			try {
				sub.onNext(List.of(ByteBuffer.wrap(huge)));
			} catch (Throwable t) {
				// expected when the consumer closes
			}
		});
		producer.setDaemon(true);
		producer.start();

		// Iterate the stream and expect LineTooLongException to surface.
		CompletionStage<Stream<String>> bodyStage = sub.getBody();
		Stream<String> stream = bodyStage.toCompletableFuture().join();

		boolean caught = false;
		try {
			stream.toList();
		} catch (Throwable t) {
			caught = true;
		}
		org.assertj.core.api.Assertions.assertThat(caught).isTrue();

		assertThat(mock.cancelled.get()).isEqualTo(1);
		producer.join(2000);
	}

	@Test
	@DisplayName("partial line at EOF with size under limit is emitted (BufferedReader semantics)")
	void partialLineAtEof() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("hello");
	}

	@Test
	@DisplayName("partial line at EOF over the limit throws LineTooLongException")
	void partialLineAtEofOverLimit() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(5, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcdefghij".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS).toList();
		}).isInstanceOf(LineTooLongException.class);
	}

	@Test
	@DisplayName("onError surfaces as UncheckedIOException in the stream")
	void onErrorSurfacesAsUnchecked() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onError(new java.io.IOException("test io error"));
		assertThatThrownBy(() ->
				                   sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS).toList()
		).isInstanceOf(java.io.UncheckedIOException.class);
	}

	@Test
	@DisplayName("double onSubscribe cancels the second subscription")
	void doubleOnSubscribe() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock1 = new MockSubscription();
		MockSubscription mock2 = new MockSubscription();
		sub.onSubscribe(mock1);
		sub.onSubscribe(mock2);
		assertThat(mock2.cancelled.get()).isEqualTo(1);

		sub.onNext(List.of(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();
		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("hello");
	}

	@Test
	@DisplayName("stream close via onClose cancels the upstream subscription")
	void streamCloseCancelsUpstream() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().join();
		stream.close();
		assertThat(mock.cancelled.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("empty body yields an empty stream")
	void emptyBody() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onComplete();
		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).isEmpty();
	}

	@Test
	@DisplayName("line over limit with CR terminator throws LineTooLongException")
	void lineOverLimitWithCr() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(5, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcdef\r".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS).toList();
		}).isInstanceOf(LineTooLongException.class);
	}

	@Test
	@DisplayName("multiple CRLF sequences in stream")
	void multipleCrlf() throws Exception {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(20, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onNext(List.of(ByteBuffer.wrap("line1\r\nline2\r\n\r\nline3\r\n".getBytes(StandardCharsets.UTF_8))));
		sub.onComplete();

		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertThat(stream.toList()).containsExactly("line1", "line2", "", "line3");
	}

	@Test
	@DisplayName("onError without prior onSubscribe is handled safely")
	void onErrorWithoutSubscribe() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);

		sub.onError(new java.io.IOException("early error"));
		assertThatThrownBy(() ->
				                   sub.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS).toList()
		).isInstanceOf(java.io.UncheckedIOException.class);
	}

	@Test
	@DisplayName("repeated cancel or signal error is idempotent")
	void errorSignalsIdempotent() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		MockSubscription mock = new MockSubscription();
		sub.onSubscribe(mock);

		sub.onError(new java.io.IOException("first error"));
		sub.onError(new java.io.IOException("second error"));
		assertThat(mock.cancelled.get()).isEqualTo(0);
	}

	@Test
	@DisplayName("line length exceeds buffer length without any terminator")
	void bufferOverflowWithoutTerminator() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(2, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		sub.onSubscribe(new MockSubscription());

		// maxLineBytes=2, buffer length = 2 + 4 = 6. Feed 10 bytes without any terminator
		assertThatThrownBy(() -> {
			sub.onNext(List.of(ByteBuffer.wrap("abcdefghij".getBytes(StandardCharsets.UTF_8))));
			sub.getBody().toCompletableFuture().join().toList();
		}).isInstanceOf(LineTooLongException.class);
	}

	@Test
	@DisplayName("stream close before onSubscribe does not fail")
	void streamCloseBeforeSubscribe() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<java.util.stream.Stream<String>> sub = handler.apply(INFO);
		java.util.stream.Stream<String> stream = sub.getBody().toCompletableFuture().join();
		stream.close();
	}

	@Test
	@DisplayName("onNext handles unexpected runtime exception in buffer processing")
	void onNextUnexpectedException() {
		BoundedLineBodyHandler handler = new BoundedLineBodyHandler(10, StandardCharsets.UTF_8);
		HttpResponse.BodySubscriber<Stream<String>> sub = handler.apply(INFO);
		sub.onSubscribe(new MockSubscription());

		// A list that throws on iteration
		List<ByteBuffer> failingList = new AbstractList<>() {
			@Override
			public ByteBuffer get(int index) {
				throw new IllegalStateException("corrupted item");
			}

			@Override
			public int size() {
				return 1;
			}
		};

		sub.onNext(failingList);
		assertThatThrownBy(() -> sub.getBody().toCompletableFuture().join().toList())
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Simple {@link Flow.Subscription} mock that records {@code request()} and {@code cancel()} calls.
	 */
	private static final class MockSubscription implements Flow.Subscription {
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