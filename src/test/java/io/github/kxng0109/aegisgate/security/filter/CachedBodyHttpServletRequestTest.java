package io.github.kxng0109.aegisgate.security.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CachedBodyHttpServletRequest}: byte-for-byte fidelity,
 * repeatable reads, size caps, and defensive-copy semantics.
 */
@DisplayName("CachedBodyHttpServletRequest")
class CachedBodyHttpServletRequestTest {

	private static final String SAMPLE_JSON = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"héllo wörld\"}]}";

	@Test
	@DisplayName("ASCII body round-trips through getContentAsByteArray")
	void asciiRoundTrip() throws IOException {
		byte[] in = SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("multi-byte UTF-8 content is preserved byte-for-byte")
	void utf8Fidelity() throws IOException {
		byte[] in = "Héllo Wörld, déjà vu 😀".getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		assertArrayEquals(in, wrapper.getContentAsByteArray());
		assertEquals("Héllo Wörld, déjà vu 😀", new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("arbitrary binary bytes 0x00-0xFF are preserved")
	void binaryFidelity() throws IOException {
		byte[] in = new byte[256];
		for (int i = 0; i < in.length; i++) {
			in[i] = (byte) i;
		}
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("each getInputStream call yields a fresh, fully readable stream")
	void repeatedInputReads() throws IOException {
		byte[] in = SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));

		byte[] first = readAll(wrapper.getInputStream());
		byte[] second = readAll(wrapper.getInputStream());

		assertArrayEquals(in, first);
		assertArrayEquals(in, second);
	}

	@Test
	@DisplayName("each getReader call yields a fresh reader with identical content")
	void repeatedReaderReads() throws IOException {
		byte[] in = SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));

		String first = readLineAll(wrapper.getReader());
		String second = readLineAll(wrapper.getReader());

		assertEquals(SAMPLE_JSON, first);
		assertEquals(SAMPLE_JSON, second);
	}

	@Test
	@DisplayName("getReader works after getInputStream was already consumed")
	void readerAfterStream() throws IOException {
		byte[] in = SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		readAll(wrapper.getInputStream());
		assertEquals(SAMPLE_JSON, readLineAll(wrapper.getReader()));
	}

	@Test
	@DisplayName("empty body yields an empty byte array")
	void emptyBody() throws IOException {
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(new byte[0]));
		assertEquals(0, wrapper.getContentAsByteArray().length);
	}

	@Test
	@DisplayName("declared content-length above the cap is rejected with BodyTooLargeException")
	void declaredExceedsCap() {
		byte[] in = new byte[11];
		CachedBodyHttpServletRequest wrapper;
		try {
			wrapper = new CachedBodyHttpServletRequest(requestWith(in), 10);
			assertEquals(11, wrapper.getContentAsByteArray().length, "must not reach here");
		} catch (BodyTooLargeException expected) {
			assertEquals(10, expected.getLimit());
		} catch (IOException unexpected) {
			throw new AssertionError(unexpected);
		}
	}

	@Test
	@DisplayName("chunked body (no content-length) above the cap is rejected")
	void chunkedExceedsCap() throws IOException {
		byte[] in = new byte[11];
		assertThrows(BodyTooLargeException.class,
		             () -> new CachedBodyHttpServletRequest(chunkedRequest(in), 10)
		);
	}

	@Test
	@DisplayName("body of exactly the cap size is accepted")
	void exactlyAtCap() throws IOException {
		byte[] in = new byte[10];
		for (int i = 0; i < in.length; i++) {
			in[i] = (byte) ('a' + i);
		}
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in), 10);
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("body one byte over the cap is rejected")
	void oneByteOverCap() {
		byte[] in = new byte[11];
		assertThrows(BodyTooLargeException.class,
		             () -> new CachedBodyHttpServletRequest(requestWith(in), 10)
		);
	}

	@Test
	@DisplayName("non-positive caps are rejected")
	void invalidCap() {
		assertThrows(IllegalArgumentException.class,
		             () -> new CachedBodyHttpServletRequest(requestWith(new byte[0]), 0)
		);
		assertThrows(IllegalArgumentException.class,
		             () -> new CachedBodyHttpServletRequest(requestWith(new byte[0]), -1)
		);
	}

	@Test
	@DisplayName("getContentAsByteArray returns a defensive copy")
	void defensiveCopy() throws IOException {
		byte[] in = SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		byte[] returned = wrapper.getContentAsByteArray();
		Arrays.fill(returned, (byte) 0);
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("isFinished reflects the current stream state")
	void isFinishedTransitions() throws IOException {
		byte[] in = new byte[5];
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		ServletInputStream stream = wrapper.getInputStream();
		assertFalse(stream.isFinished());
		byte[] buf = new byte[10];
		assertEquals(5, stream.read(buf));
		assertTrue(stream.isFinished());
		assertEquals(-1, stream.read(buf));
	}

	@Test
	@DisplayName("setReadListener is unsupported (synchronous reads only)")
	void readListenerUnsupported() throws IOException {
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(new byte[8]));
		assertThrows(UnsupportedOperationException.class,
		             () -> wrapper.getInputStream().setReadListener(mockReadListener())
		);
	}

	@Test
	@DisplayName("declared length larger than actual bytes is trimmed to the received content")
	void declaredLengthShortReadTrimmed() throws IOException {
		byte[] actual = "short".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions") {
			@Override
			public long getContentLengthLong() {
				return 100;
			}

			@Override
			public ServletInputStream getInputStream() {
				return fixedStream(actual);
			}
		};
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(request);
		assertArrayEquals(actual, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("chunked body smaller than the cap is trimmed to its actual size")
	void chunkedShortBodyTrimmed() throws IOException {
		byte[] in = "tiny".getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(chunkedRequest(in), 10);
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("large chunked body triggers the internal buffer growth path")
	void chunkedLargeBodyGrowth() throws IOException {
		byte[] in = new byte[20_000];
		Arrays.fill(in, (byte) 'x');
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(chunkedRequest(in));
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	@Test
	@DisplayName("single-byte reads work via the ServletInputStream override")
	void singleByteReads() throws IOException {
		byte[] in = "abc".getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(requestWith(in));
		ServletInputStream stream = wrapper.getInputStream();
		assertEquals('a', stream.read());
		assertEquals('b', stream.read());
		assertEquals('c', stream.read());
		assertEquals(-1, stream.read());
		assertTrue(stream.isFinished());
	}

	@Test
	@DisplayName("chunked body of exactly the internal chunk size returns the buffer as-is")
	void chunkedBodyAtChunkSize() throws IOException {
		byte[] in = new byte[8192];
		Arrays.fill(in, (byte) 'y');
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(chunkedRequest(in));
		assertArrayEquals(in, wrapper.getContentAsByteArray());
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static MockHttpServletRequest requestWith(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
		request.setServletPath("/v1/chat/completions");
		request.setContent(body);
		return request;
	}

	private static MockHttpServletRequest chunkedRequest(byte[] body) {
		return new MockHttpServletRequest("POST", "/v1/chat/completions") {
			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public ServletInputStream getInputStream() {
				return fixedStream(body);
			}
		};
	}

	static ServletInputStream fixedStream(byte[] data) {
		ByteArrayInputStream in = new ByteArrayInputStream(data);
		return new ServletInputStream() {
			@Override
			public int read() {
				return in.read();
			}

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				throw new UnsupportedOperationException("Asynchronous reads are not supported");
			}
		};
	}

	private static byte[] readAll(ServletInputStream stream) throws IOException {
		return stream.readAllBytes();
	}

	private static String readLineAll(BufferedReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			sb.append(line);
		}
		return sb.toString();
	}

	private static ReadListener mockReadListener() {
		return new ReadListener() {
			@Override
			public void onDataAvailable() {
			}

			@Override
			public void onAllDataRead() {
			}

			@Override
			public void onError(Throwable t) {
			}
		};
	}
}