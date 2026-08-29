package io.github.kxng0109.aegisgate.security.filter;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RequestBodyCachingFilter}: path/method gating, wrapping,
 * replayability through the chain, and the 413 rejection path.
 */
@DisplayName("RequestBodyCachingFilter")
class RequestBodyCachingFilterTest {

	private static final String PATH = "/v1/chat/completions";

	private final RequestBodyCachingFilter filter = new RequestBodyCachingFilter();

	@Test
	@DisplayName("POST to the target path is wrapped with the exact body")
	void postToPathWraps() throws Exception {
		MockHttpServletRequest request = postRequest("{\"model\":\"gpt-4o\"}");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertInstanceOf(CachedBodyHttpServletRequest.class, chain.getRequest());
		CachedBodyHttpServletRequest wrapped = (CachedBodyHttpServletRequest) chain.getRequest();
		assertArrayEquals("{\"model\":\"gpt-4o\"}".getBytes(StandardCharsets.UTF_8),
				wrapped.getContentAsByteArray());
	}

	@Test
	@DisplayName("GET to the target path is passed through unwrapped")
	void getToPathUnwrapped() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
		request.setServletPath(PATH);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertSame(request, chain.getRequest(), "GET must pass the original request through");
	}

	@Test
	@DisplayName("POST to another path is passed through unwrapped")
	void postToOtherPathUnwrapped() throws Exception {
		MockHttpServletRequest request = postRequestWithPath("/different", "{}");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertSame(request, chain.getRequest(), "other paths must pass the original request through");
	}

	@Test
	@DisplayName("the controller can re-read the body after the filter consumed it")
	void replayThroughChain() throws Exception {
		byte[] body = "{\"model\":\"gpt-4o\",\"max_tokens\":500}".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = postRequest("{\"model\":\"gpt-4o\",\"max_tokens\":500}");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		CachedBodyHttpServletRequest wrapped = (CachedBodyHttpServletRequest) chain.getRequest();
		assertArrayEquals(body, wrapped.getContentAsByteArray());
		assertArrayEquals(body, wrapped.getInputStream().readAllBytes());
	}

	@Test
	@DisplayName("declared body over the cap yields 413 and short-circuits")
	void tooLargeDeclared() throws Exception {
		RequestBodyCachingFilter small = new RequestBodyCachingFilter(10);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
		request.setServletPath(PATH);
		request.setContent(new byte[11]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		small.doFilter(request, response, chain);

		assertEquals(413, response.getStatus());
		assertNull(chain.getRequest(), "chain must not run when filter short-circuits");
		assertTrue(response.getContentType().contains("application/json"));
		assertTrue(response.getContentAsString().contains("request body too large"));
	}

	@Test
	@DisplayName("chunked body over the cap yields 413 and short-circuits")
	void tooLargeChunked() throws Exception {
		RequestBodyCachingFilter small = new RequestBodyCachingFilter(10);
		MockHttpServletRequest request = chunkedRequest(new byte[11]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		small.doFilter(request, response, chain);

		assertEquals(413, response.getStatus());
		assertNull(chain.getRequest(), "chain must not run when filter short-circuits");
	}

	@Test
	@DisplayName("small chunked body (no declared length) is wrapped fine")
	void smallChunkedWrapped() throws Exception {
		MockHttpServletRequest request = chunkedRequest("{\"model\":\"gpt-4o\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		CachedBodyHttpServletRequest wrapped = (CachedBodyHttpServletRequest) chain.getRequest();
		assertArrayEquals("{\"model\":\"gpt-4o\"}".getBytes(StandardCharsets.UTF_8),
				wrapped.getContentAsByteArray());
	}

	@Test
	@DisplayName("non-positive caps are rejected at construction")
	void invalidCapRejected() {
		assertThrows(IllegalArgumentException.class, () -> new RequestBodyCachingFilter(0));
		assertThrows(IllegalArgumentException.class, () -> new RequestBodyCachingFilter(-1));
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static MockHttpServletRequest postRequest(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
		request.setServletPath(PATH);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static MockHttpServletRequest postRequestWithPath(String path, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setServletPath(path);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static MockHttpServletRequest chunkedRequest(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public ServletInputStream getInputStream() {
				return CachedBodyHttpServletRequestTest.fixedStream(body);
			}
		};
		request.setServletPath(PATH);
		return request;
	}
}