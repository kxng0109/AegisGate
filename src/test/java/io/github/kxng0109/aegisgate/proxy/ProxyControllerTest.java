package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import io.github.kxng0109.aegisgate.security.HeaderSanitizer;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyControllerTest {

	private final SsrfValidator noOpSsrf = new AllowAllSsrfValidator();
	private MockWebServer mockWebServer;

	private static String writeBody(ResponseEntity<StreamingResponseBody> response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		response.getBody().writeTo(out);
		return out.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	@BeforeEach
	void setUp() throws IOException {
		mockWebServer = new MockWebServer();
		mockWebServer.start(InetAddress.getByName("0.0.0.0"), 0);
	}

	@AfterEach
	void tearDown() throws IOException {
		mockWebServer.shutdown();
	}

	private UpstreamConfig upstreamConfig() {
		return new UpstreamConfig("test", baseUrl(), new SensitiveString("test-key"),
		                          Duration.ofSeconds(5), Duration.ofSeconds(5), "/v1/chat/completions"
		);
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + mockWebServer.getPort();
	}

	private ProxyService realProxyService() {
		HttpClient httpClient = HttpClient.newBuilder()
		                                  .version(HttpClient.Version.HTTP_2)
		                                  .connectTimeout(Duration.ofSeconds(5))
		                                  .executor(Executors.newVirtualThreadPerTaskExecutor())
		                                  .followRedirects(HttpClient.Redirect.NEVER)
		                                  .build();
		UpstreamConfig config = upstreamConfig();
		return new ProxyService(httpClient, config, noOpSsrf, new HeaderSanitizer(config));
	}

	private ProxyController controllerWith(ProxyService proxyService) {
		UpstreamConfig config = upstreamConfig();
		return new ProxyController(proxyService, config,
		                           new HeaderSanitizer(config), noOpSsrf
		);
	}

	private ProxyController controllerWith(SsrfValidator validator) {
		UpstreamConfig config = upstreamConfig();
		ThrowingProxyService dead = new ThrowingProxyService(new RuntimeException("unused"));
		return new ProxyController(dead, config, new HeaderSanitizer(config), validator);
	}

	@Test
	@DisplayName("returns 400 when the request body is blank")
	void rejectsBlankBody() throws Exception {
		ProxyController controller = controllerWith(realProxyService());

		ResponseEntity<StreamingResponseBody> response = controller.proxy("   ", Map.of());

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertTrue(writeBody(response).contains("Body required!"));
	}

	@Test
	@DisplayName("returns 400 when the request body is null")
	void rejectsNullBody() {
		ProxyController controller = controllerWith(realProxyService());

		ResponseEntity<StreamingResponseBody> response = controller.proxy(null, Map.of());

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
	}

	@Test
	@DisplayName("streams a 200 SSE response from the upstream verbatim")
	void streamsSuccessResponse() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
						                               + "data: [DONE]\n\n"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("text/event-stream",
		             response.getHeaders().getFirst("Content-Type")
		);

		String streamed = writeBody(response);
		assertTrue(streamed.contains("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));
		assertTrue(streamed.contains("data: [DONE]"));
		assertEquals("no-cache", response.getHeaders().getFirst("Cache-Control"));
	}

	@Test
	@DisplayName("handles an upstream that repeats a header name without error")
	void keepsFirstValueOfDuplicateHeader() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .addHeader("Content-Type", "application/json")
				                      .setBody("data: [DONE]\n\n"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertTrue(writeBody(response).contains("data: [DONE]"));
	}

	@Test
	@DisplayName("forwards a non-200 upstream status verbatim with its body")
	void forwardsErrorStatus() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(500)
				                      .addHeader("Content-Type", "application/json")
				                      .setBody("{\"error\":{\"message\":\"boom\"}}"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		assertTrue(writeBody(response).contains("boom"));
	}

	@Test
	@DisplayName("forwards a 429 upstream status verbatim")
	void forwards429() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(429)
				                      .addHeader("Retry-After", "60")
				                      .setBody("rate limited"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
		assertTrue(writeBody(response).contains("rate limited"));
	}

	@Test
	@DisplayName("forwards a 401 upstream status verbatim")
	void forwards401() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(401)
				                      .setBody("unauthorized"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		assertTrue(writeBody(response).contains("unauthorized"));
	}

	@Test
	@DisplayName("aborts the stream and swallows the error when the client disconnects during success")
	void clientAbortsDuringSuccess() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody("data: [DONE]\n\n"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		// Must not propagate: the controller catches the client-abort IOException.
		response.getBody().writeTo(failing);
	}

	@Test
	@DisplayName("aborts the stream and swallows the error when the client disconnects during error body")
	void clientAbortsDuringErrorBody() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(500)
				                      .setBody("{\"error\":{\"message\":\"boom\"}}"));

		ProxyController controller = controllerWith(realProxyService());
		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		OutputStream failing = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("client gone");
			}
		};
		response.getBody().writeTo(failing);
	}

	@Test
	@DisplayName("returns 403 when the controller's own SSRF validation blocks the upstream")
	void ssrfBlockedByControllerValidator() throws Exception {
		SsrfValidator blocking = new SsrfValidator() {
			@Override
			public void validate(URI targetUrl) {
				throw new SsrfViolationException("blocked host");
			}
		};
		ProxyController controller = controllerWith(blocking);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		assertTrue(writeBody(response).contains("blocked host"));
	}

	@Test
	@DisplayName("returns 403 when the proxy service reports an SSRF violation")
	void ssrfBlockedByProxyService() throws Exception {
		ProxyService service = new ThrowingProxyService(new SsrfViolationException("blocked"));
		ProxyController controller = controllerWith(service);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		assertTrue(writeBody(response).contains("blocked"));
	}

	@Test
	@DisplayName("returns 504 on upstream connection timeout")
	void gatewayTimeoutOnConnectTimeout() throws Exception {
		ProxyService service = new ThrowingProxyService(
				new HttpTimeoutException("HTTP connect timed out"));
		ProxyController controller = controllerWith(service);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
		assertTrue(writeBody(response).contains("Gateway timeout"));
	}

	@Test
	@DisplayName("returns 502 on upstream interruption")
	void badGatewayOnInterrupt() throws Exception {
		ProxyService service = new ThrowingProxyService(new InterruptedException("cancelled"));
		ProxyController controller = controllerWith(service);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertTrue(writeBody(response).contains("Upstream error"));
	}

	@Test
	@DisplayName("returns 502 on unknown upstream host")
	void badGatewayOnUnknownHost() throws Exception {
		ProxyService service = new ThrowingProxyService(new UnknownHostException("nope"));
		ProxyController controller = controllerWith(service);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertTrue(writeBody(response).contains("Unknown host"));
	}

	@Test
	@DisplayName("returns 502 on generic upstream I/O failure")
	void badGatewayOnIoFailure() throws Exception {
		ProxyService service = new ThrowingProxyService(new InterruptedIOException("closed"));
		ProxyController controller = controllerWith(service);

		ResponseEntity<StreamingResponseBody> response = controller.proxy(body(), Map.of());

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertTrue(writeBody(response).contains("Upstream error"));
	}

	private String body() {
		return "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"stream\":true}";
	}

	/**
	 * ProxyService whose {@code proxy} always throws the configured exception,
	 * used to drive the controller's exception-mapping branches without a network.
	 */
	static final class ThrowingProxyService extends ProxyService {
		private final Throwable toThrow;

		ThrowingProxyService(Throwable toThrow) {
			super(HttpClient.newBuilder().build(),
			      new UpstreamConfig("test", "https://api.openai.com/v1",
			                         new SensitiveString("test-key"), Duration.ofSeconds(5),
			                         Duration.ofSeconds(5), "/v1/chat/completions"
			      ),
			      new AllowAllSsrfValidator(),
			      new HeaderSanitizer(new UpstreamConfig("test", "https://api.openai.com/v1",
			                                             new SensitiveString("test-key"), Duration.ofSeconds(5),
			                                             Duration.ofSeconds(5), "/v1/chat/completions"
			      ))
			);
			this.toThrow = toThrow;
		}

		@Override
		public HttpResponse<Stream<String>> proxy(ProxyRequest request, Map<String, String> clientHeaders)
				throws IOException, InterruptedException {
			if (toThrow instanceof RuntimeException re) {
				throw re;
			}
			if (toThrow instanceof IOException io) {
				throw io;
			}
			if (toThrow instanceof InterruptedException ie) {
				throw ie;
			}
			throw new IOException(toThrow);
		}
	}
}
