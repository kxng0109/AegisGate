package io.github.kxng0109.aegisgate.security.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Request wrapper that buffers the body once at construction and serves a
 * <em>fresh</em> body stream on every access.
 *
 * <p>Why this exists: the gateway must read the JSON request body twice per
 * request  -  once in the auth/rate-limit filter (to extract {@code model} and {@code max_tokens}) and once in Spring
 * MVC's {@code @RequestBody} argument resolver. Spring's {@code ContentCachingRequestWrapper} cannot do this: it lazily
 * caches bytes as they are read and returns the <em>same exhausted stream</em> to second readers, which would silently
 * deliver an empty body to the controller. Buffering eagerly at construction makes every call to
 * {@link #getInputStream()} / {@link #getReader()} independent and byte-for-byte faithful.</p>
 *
 * <p>The body is capped at {@link #DEFAULT_MAX_BODY_BYTES} (1 MiB) to bound
 * memory. A declared or streamed body exceeding the cap is rejected with {@link BodyTooLargeException} and the
 * underlying input stream is closed.</p>
 *
 * <p>Not thread-safe by design: a request is processed on a single (virtual)
 * thread, so no synchronization is added.</p>
 */
public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	/**
	 * Default upper bound for a buffered request body (1 MiB).
	 */
	public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

	private static final int READ_CHUNK_SIZE = 8192;

	private final byte[] body;

	/**
	 * Buffers the request body with the default 1 MiB cap.
	 *
	 * @param request the original servlet request
	 * @throws IOException           if the body cannot be read
	 * @throws BodyTooLargeException if the body exceeds the cap
	 */
	public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
		this(request, DEFAULT_MAX_BODY_BYTES);
	}

	/**
	 * Buffers the request body with an explicit cap.
	 *
	 * @param request      the original servlet request
	 * @param maxBodyBytes maximum number of body bytes to buffer (must be {@code > 0})
	 * @throws IllegalArgumentException if {@code maxBodyBytes <= 0}
	 * @throws IOException              if the body cannot be read
	 * @throws BodyTooLargeException    if the body exceeds the cap
	 */
	public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBodyBytes) throws IOException {
		super(request);
		if (maxBodyBytes <= 0) {
			throw new IllegalArgumentException("maxBodyBytes must be > 0, was " + maxBodyBytes);
		}
		this.body = bufferBody(request, maxBodyBytes);
	}

	private static byte[] bufferBody(HttpServletRequest request, int maxBodyBytes) throws IOException {
		long declared = request.getContentLengthLong();
		try (InputStream in = request.getInputStream()) {
			if (declared > maxBodyBytes) {
				throw new BodyTooLargeException(maxBodyBytes);
			}
			if (declared == -1) {
				return readUnknownLength(in, maxBodyBytes);
			}
			return readFixedLength(in, declared, maxBodyBytes);
		}
	}

	private static byte[] readFixedLength(InputStream in, long declared, int maxBodyBytes) throws IOException {
		int expected = (int) Math.min(declared, Integer.MAX_VALUE);
		byte[] buffer = new byte[expected];
		int offset = 0;
		while (offset < expected) {
			int read = in.read(buffer, offset, expected - offset);
			if (read == -1) {
				break;
			}
			offset += read;
		}
		if (offset < expected) {
			// Declared length exceeded actual bytes; keep what was received.
			return Arrays.copyOf(buffer, offset);
		}
		return buffer;
	}

	private static byte[] readUnknownLength(InputStream in, int maxBodyBytes) throws IOException {
		byte[] chunk = new byte[READ_CHUNK_SIZE];
		byte[] accumulated = new byte[Math.min(READ_CHUNK_SIZE, maxBodyBytes)];
		int size = 0;
		int read;
		while ((read = in.read(chunk)) != -1) {
			int newSize = size + read;
			if (newSize > maxBodyBytes) {
				throw new BodyTooLargeException(maxBodyBytes);
			}
			if (newSize > accumulated.length) {
				accumulated = Arrays.copyOf(accumulated, Math.max(newSize, accumulated.length * 2));
			}
			System.arraycopy(chunk, 0, accumulated, size, read);
			size = newSize;
		}
		return size == accumulated.length ? accumulated : Arrays.copyOf(accumulated, size);
	}

	/**
	 * @return a defensive copy of the buffered body; never {@code null}
	 */
	public byte[] getContentAsByteArray() {
		return body.clone();
	}

	/**
	 * @return a fresh, fully readable input stream over the buffered body
	 */
	@Override
	public ServletInputStream getInputStream() {
		return new BufferedServletInputStream(new ByteArrayInputStream(body));
	}

	/**
	 * @return a fresh {@code UTF-8} reader over the buffered body
	 * @throws IOException never (API compatibility with the servlet contract)
	 */
	@Override
	public BufferedReader getReader() throws IOException {
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
	}

	/**
	 * {@link ServletInputStream} over a fixed byte source. Always ready, never asynchronous.
	 */
	private static final class BufferedServletInputStream extends ServletInputStream {

		private final ByteArrayInputStream delegate;

		BufferedServletInputStream(ByteArrayInputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public int read() {
			return delegate.read();
		}

		@Override
		public int read(byte[] b, int off, int len) {
			return delegate.read(b, off, len);
		}

		@Override
		public boolean isFinished() {
			return delegate.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new UnsupportedOperationException("Asynchronous reads are not supported");
		}
	}
}