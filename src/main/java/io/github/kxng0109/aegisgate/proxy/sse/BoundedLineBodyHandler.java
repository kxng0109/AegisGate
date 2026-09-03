package io.github.kxng0109.aegisgate.proxy.sse;

import org.jspecify.annotations.Nullable;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Body handler that enforces a maximum line length during byte decoding, preventing out-of-memory attacks from
 * oversized SSE lines.
 *
 * <p>Replaces {@link HttpResponse.BodyHandlers#ofLines()} which uses
 * {@code BufferedReader.lines()} internally. That implementation accumulates the current line in an unbounded
 * {@code StringBuilder}, so an attacker sending a 100 MB line without a newline would exhaust the heap before any
 * consumer sees the data.</p>
 *
 * <p>This handler enforces the limit <em>during byte accumulation</em> by
 * scanning for the newline or carriage return bytes directly. UTF-8 guarantees that the line terminator bytes never
 * appear inside a multi-byte sequence, so byte-level scanning is exact. When a line exceeds {@code maxLineBytes}, the
 * subscriber completes exceptionally with {@link LineTooLongException}, the upstream subscription is cancelled (causing
 * the JDK HttpClient to send an HTTP/2 RST_STREAM frame), and the consuming relay loop catches the exception and emits
 * an SSE error event to the downstream client.</p>
 *
 * <p>The implementation follows the JDK's own {@code HttpResponseInputStream}
 * pattern: a bounded queue for backpressure, lock-step demand ({@code request(1)} per consumed item), and a sentinel
 * object signalling end-of-stream. The {@code getBody()} method completes the future immediately so the relay loop can
 * start iterating before the upstream body has finished arriving.</p>
 */
public final class BoundedLineBodyHandler
		implements HttpResponse.BodyHandler<Stream<String>> {

	private final int maxLineBytes;
	private final Charset charset;

	/**
	 * Creates a new bounded line body handler.
	 *
	 * @param maxLineBytes maximum line length in bytes (must be positive)
	 * @param charset      the character set to use for decoding (typically UTF-8)
	 */
	public BoundedLineBodyHandler(int maxLineBytes, Charset charset) {
		if (maxLineBytes <= 0) {
			throw new IllegalArgumentException("maxLineBytes must be positive");
		}
		this.maxLineBytes = maxLineBytes;
		this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
	}

	/**
	 * Returns the maximum line length in bytes enforced by this handler.
	 */
	public int maxLineBytes() {
		return maxLineBytes;
	}

	@Override
	public HttpResponse.BodySubscriber<Stream<String>> apply(HttpResponse.ResponseInfo responseInfo) {
		return new BoundedLineSubscriber(maxLineBytes, charset);
	}

	/**
	 * Subscriber that enforces the line-length limit during byte decoding.
	 */
	private static final class BoundedLineSubscriber
			implements HttpResponse.BodySubscriber<Stream<String>> {

		private static final Object TERMINAL = new Object();

		private final int maxLineBytes;
		private final CompletableFuture<Stream<String>> bodyFuture = new CompletableFuture<>();
		private final Stream<String> stream;
		private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
		private final AtomicBoolean subscribed = new AtomicBoolean(false);
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		// Unbounded queue: each line is at most maxLineBytes + 4 bytes, and a
		// single onNext batch is bounded by the JDK receive buffer (typically
		// 16-64 KiB). The unbounded queue avoids deadlock when the producer
		// pushes multiple lines before the consumer can take.
		private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
		private final byte[] lineBuffer;
		private int lineLen;
		private boolean pendingCr;
		private volatile boolean eof;

		BoundedLineSubscriber(int maxLineBytes, Charset charset) {
			this.maxLineBytes = maxLineBytes;
			this.lineBuffer = new byte[maxLineBytes + 4];
			this.lineLen = 0;
			this.stream = StreamSupport.stream(new LineSpliterator(), false)
			                           .onClose(this::cancelUpstream);
			this.bodyFuture.complete(stream);
		}

		@Override
		public CompletionStage<Stream<String>> getBody() {
			return bodyFuture;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			if (!subscribed.compareAndSet(false, true)) {
				s.cancel();
				return;
			}
			subscription.set(s);
			s.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			try {
				for (ByteBuffer item : items) {
					processBytes(item);
				}
				Flow.Subscription s = subscription.get();
				if (s != null) {
					s.request(1);
				}
			} catch (LineTooLongException ex) {
				signalViolation(ex);
			} catch (Throwable t) {
				signalError(t);
			}
		}

		@Override
		public void onError(Throwable t) {
			signalError(t);
		}

		@Override
		public void onComplete() {
			eof = true;
			if (lineLen > 0) {
				emitLine();
			}
			enqueueTerminal();
		}

		private void processBytes(ByteBuffer buffer) {
			while (buffer.hasRemaining()) {
				byte b = buffer.get();
				if (pendingCr) {
					pendingCr = false;
					if (b == '\n') {
						continue;
					}
				}
				if (b == '\n') {
					emitLine();
					continue;
				}
				if (b == '\r') {
					pendingCr = true;
					emitLine();
					continue;
				}
				if (lineLen >= maxLineBytes) {
					throw new LineTooLongException(maxLineBytes, lineLen + 1, "unknown");
				}
				lineBuffer[lineLen++] = b;
			}
		}

		private void emitLine() {
			String line = new String(lineBuffer, 0, lineLen, StandardCharsets.UTF_8);
			queue.offer(line);
			lineLen = 0;
		}

		private void terminate(@org.jspecify.annotations.Nullable Throwable t, boolean cancel) {
			if (t != null && !failure.compareAndSet(null, t)) {
				return;
			}
			eof = true;
			if (cancel) {
				Flow.Subscription s = subscription.getAndSet(null);
				if (s != null) {
					s.cancel();
				}
			}
			enqueueTerminal();
		}

		private void signalViolation(LineTooLongException ex) {
			terminate(ex, true);
		}

		private void signalError(Throwable t) {
			terminate(t, false);
		}

		private void cancelUpstream() {
			terminate(null, true);
		}

		private void enqueueTerminal() {
			queue.offer(TERMINAL);
		}

		private void rethrowIfFailed() {
			Throwable err = failure.get();
			if (err != null) {
				if (err instanceof LineTooLongException ltle) {
					throw ltle;
				}
				if (err instanceof java.io.IOException ioe) {
					throw new java.io.UncheckedIOException(ioe);
				}
				if (err instanceof RuntimeException re) {
					throw re;
				}
				throw new RuntimeException(err);
			}
		}

		private final class LineSpliterator implements java.util.Spliterator<String> {

			@Override
			public boolean tryAdvance(java.util.function.Consumer<? super String> action) {
				while (true) {
					rethrowIfFailed();
					Object item = queue.poll();
					if (item == null) {
						if (eof) {
							return false;
						}
						try {
							item = queue.take();
						} catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							throw new RuntimeException(ex);
						}
					}
					if (item == TERMINAL) {
						rethrowIfFailed();
						return false;
					}
					action.accept((String) item);
					return true;
				}
			}

			@Override
			public java.util.@Nullable Spliterator<String> trySplit() {
				return null;
			}

			@Override
			public long estimateSize() {
				return Long.MAX_VALUE;
			}

			@Override
			public int characteristics() {
				return ORDERED | NONNULL;
			}
		}
	}
}