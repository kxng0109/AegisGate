package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Test doubles for {@link ServletOutputStream} shared by the SSE flush tests.
 *
 * <p>A real {@code ServletOutputStream} cannot be instantiated outside a servlet container; these fakes implement the
 * three abstract methods and let tests observe writes, flushes, and closes deterministically.</p>
 */
public final class TestServletOutputStreams {

	private TestServletOutputStreams() {
	}

	/**
	 * Fast in-memory servlet output stream recording written bytes and flush calls.
	 */
	public static class RecordingServletOutputStream extends ServletOutputStream {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private final java.util.concurrent.atomic.AtomicInteger flushCount =
				new java.util.concurrent.atomic.AtomicInteger();

		private volatile boolean closed;

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
			// Synchronous stream: the async write listener contract is never exercised.
		}

		@Override
		public void write(int b) throws IOException {
			checkOpen();
			buffer.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			checkOpen();
			buffer.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			checkOpen();
			flushCount.incrementAndGet();
		}

		@Override
		public void close() throws IOException {
			closed = true;
		}

		/**
		 * @return number of flush calls observed
		 */
		public int flushCount() {
			return flushCount.get();
		}

		/**
		 * @return whether the stream was closed
		 */
		public boolean isClosed() {
			return closed;
		}

		/**
		 * @return the written bytes decoded as UTF-8
		 */
		public String writtenUtf8() {
			return buffer.toString(StandardCharsets.UTF_8);
		}

		protected void checkOpen() throws IOException {
			if (closed) {
				throw new IOException("stream closed");
			}
		}
	}

	/**
	 * {@code flush()} blocks until {@link #releaseFlush()} or {@link #close()} releases it, then fails if the stream
	 * was closed. Used to simulate a stuck downstream write and the watchdog abort.
	 */
	public static class BlockingServletOutputStream extends RecordingServletOutputStream {

		private final CountDownLatch flushStarted = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		@Override
		public void flush() throws IOException {
			checkOpen();
			flushStarted.countDown();
			try {
				release.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while blocked in flush", ex);
			}
			checkOpen();
			super.flush();
		}

		@Override
		public void close() throws IOException {
			super.close();
			release.countDown();
		}

		/**
		 * Blocks until the first flush call entered the blocked state.
		 *
		 * @throws InterruptedException when the waiting thread is interrupted
		 */
		public void awaitFlushStarted() throws InterruptedException {
			flushStarted.await();
		}

		/**
		 * Lets a blocked flush complete normally.
		 */
		public void releaseFlush() {
			release.countDown();
		}
	}

	/**
	 * A blocking stream whose {@code close()} also fails; covers the watchdog's swallow path.
	 */
	public static class CloseFailingBlockingServletOutputStream extends BlockingServletOutputStream {

		@Override
		public void close() throws IOException {
			super.close();
			throw new IOException("close failed");
		}
	}

	/**
	 * {@code flush()} sleeps the given number of milliseconds, simulating a slow downstream client.
	 */
	public static class SlowServletOutputStream extends RecordingServletOutputStream {

		private final long millis;

		/**
		 * Creates the stream.
		 *
		 * @param millis how long each flush takes
		 */
		public SlowServletOutputStream(long millis) {
			this.millis = millis;
		}

		@Override
		public void flush() throws IOException {
			checkOpen();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while sleeping in flush", ex);
			}
			super.flush();
		}
	}

	/**
	 * Every flush fails immediately, simulating a client that already disconnected.
	 */
	public static class FailingFlushServletOutputStream extends RecordingServletOutputStream {

		@Override
		public void flush() throws IOException {
			throw new IOException("client gone");
		}
	}
}