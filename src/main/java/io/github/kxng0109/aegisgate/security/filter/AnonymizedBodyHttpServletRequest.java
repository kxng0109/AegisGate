package io.github.kxng0109.aegisgate.security.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Servlet request wrapper serving an anonymized UTF-8 JSON request body.
 */
public final class AnonymizedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	public AnonymizedBodyHttpServletRequest(HttpServletRequest request, byte[] anonymizedBody) {
		super(request);
		this.body = anonymizedBody != null ? anonymizedBody.clone() : new byte[0];
	}

	public byte[] getContentAsByteArray() {
		return body.clone();
	}

	@Override
	public int getContentLength() {
		return body.length;
	}

	@Override
	public long getContentLengthLong() {
		return body.length;
	}

	@Override
	public ServletInputStream getInputStream() {
		return new ByteArrayServletInputStream(new ByteArrayInputStream(body));
	}

	@Override
	public BufferedReader getReader() throws IOException {
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
	}

	private static final class ByteArrayServletInputStream extends ServletInputStream {

		private final ByteArrayInputStream delegate;

		ByteArrayServletInputStream(ByteArrayInputStream delegate) {
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
