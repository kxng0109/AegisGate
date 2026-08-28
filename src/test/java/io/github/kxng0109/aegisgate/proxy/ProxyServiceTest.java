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

import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProxyService} exercising the real
 * {@link java.net.http.HttpClient} against an in-process {@link MockWebServer}.
 *
 * <p>The production {@link SsrfValidator} is intentionally not used here: it is
 * a defense-in-depth control already covered by {@code SsrfValidatorTest}, and
 * it would block the loopback address the mock server binds to. A test-only
 * validator that permits every target lets these tests exercise the proxy
 * streaming/forwarding logic without weakening the real SSRF boundary.
 */
class ProxyServiceTest {

	private MockWebServer mockWebServer;
	private ProxyService proxyService;
	private SsrfValidator ssrfValidator;

	@BeforeEach
	void setUp() throws IOException {
		mockWebServer = new MockWebServer();
		mockWebServer.start(InetAddress.getByName("0.0.0.0"), 0);

		UpstreamConfig upstreamConfig = upstreamConfig(baseUrl());
		HttpClient httpClient = httpClient(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ssrfValidator = new AllowAllSsrfValidator();
		proxyService = new ProxyService(httpClient, upstreamConfig, ssrfValidator,
		                                new HeaderSanitizer(upstreamConfig)
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		mockWebServer.shutdown();
	}

	@Test
	@DisplayName("streams an SSE response from the upstream verbatim")
	void proxiesSseStreamingResponse() throws Exception {
		String sseResponse = "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"index\":0}]}\n\n"
				+ "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\" World\"},\"index\":0}]}\n\n"
				+ "data: [DONE]\n\n";

		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody(sseResponse));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		var response = proxyService.proxy(request, Map.of());
		String streamed = response.body().collect(Collectors.joining("\n"));

		assertAll(
				() -> assertEquals(200, response.statusCode()),
				() -> assertTrue(response.headers().firstValue("Content-Type")
				                         .orElse("").contains("text/event-stream")),
				() -> assertTrue(streamed.contains("data: {\"id\":\"chatcmpl-1\"")),
				() -> assertTrue(streamed.contains("Hello")),
				() -> assertTrue(streamed.contains(" World")),
				() -> assertTrue(streamed.contains("[DONE]"))
		);
	}

	@Test
	@DisplayName("forwards an upstream 500 status verbatim")
	void forwardsUpstreamErrorStatusVerbatim() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(500)
				                      .addHeader("Content-Type", "application/json")
				                      .setBody("{\"error\":{\"message\":\"Internal server error\"}}"));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		var response = proxyService.proxy(request, Map.of());

		assertEquals(500, response.statusCode());
	}

	@Test
	@DisplayName("forwards an upstream 429 status verbatim")
	void forwardsUpstream429StatusVerbatim() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(429)
				                      .addHeader("Retry-After", "60")
				                      .addHeader("Content-Type", "application/json")
				                      .setBody("{\"error\":{\"message\":\"Rate limit exceeded\"}}"));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		var response = proxyService.proxy(request, Map.of());

		assertEquals(429, response.statusCode());
	}

	@Test
	@DisplayName("forwards an upstream 401 status verbatim")
	void forwardsUpstream401StatusVerbatim() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(401)
				                      .addHeader("Content-Type", "application/json")
				                      .setBody("{\"error\":{\"message\":\"Invalid API key\"}}"));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		var response = proxyService.proxy(request, Map.of());

		assertEquals(401, response.statusCode());
	}

	@Test
	@DisplayName("surfaces a connection timeout as HttpTimeoutException")
	void throwsOnConnectionTimeout() {
		UpstreamConfig config = upstreamConfig("http://192.0.2.1/");
		HttpClient httpClient = httpClient(Duration.ofMillis(300), Duration.ofSeconds(5));
		ProxyService service = new ProxyService(httpClient, config,
		                                        new AllowAllSsrfValidator(), new HeaderSanitizer(config)
		);

		ProxyRequest request = new ProxyRequest(body(), config, UUID.randomUUID());

		assertThrows(HttpTimeoutException.class,
		             () -> service.proxy(request, Map.of())
		);
	}

	@Test
	@DisplayName("propagates an unresolvable upstream host as IOException")
	void throwsOnUnknownHost() {
		UpstreamConfig config = upstreamConfig("http://aegisgate-nonexistent-host.invalid/");
		HttpClient httpClient = httpClient(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ProxyService service = new ProxyService(httpClient, config,
		                                        new AllowAllSsrfValidator(), new HeaderSanitizer(config)
		);

		ProxyRequest request = new ProxyRequest(body(), config, UUID.randomUUID());

		assertThrows(IOException.class, () -> service.proxy(request, Map.of()));
	}

	@Test
	@DisplayName("injects the upstream API key as a Bearer Authorization header")
	void injectsAuthorizationHeader() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody("data: [DONE]\n\n"));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		proxyService.proxy(request, Map.of());

		var recordedRequest = mockWebServer.takeRequest();
		assertEquals("Bearer test-key", recordedRequest.getHeader("Authorization"));
	}

	@Test
	@DisplayName("forces the upstream request Content-Type to application/json")
	void setsContentTypeApplicationJson() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody("data: [DONE]\n\n"));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		proxyService.proxy(request, Map.of());

		var recordedRequest = mockWebServer.takeRequest();
		assertEquals("application/json", recordedRequest.getHeader("Content-Type"));
	}

	@Test
	@DisplayName("does not follow upstream redirects, returning the 302 verbatim")
	void doesNotFollowRedirects() throws Exception {
		MockWebServer redirectTarget = new MockWebServer();
		redirectTarget.start(InetAddress.getByName("0.0.0.0"), 0);
		redirectTarget.enqueue(new MockResponse()
				                       .setResponseCode(200)
				                       .addHeader("Content-Type", "text/event-stream")
				                       .setBody("data: [DONE]\n\n"));

		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(302)
				                      .addHeader("Location", redirectTarget.url("/").toString()));

		ProxyRequest request = new ProxyRequest(body(), upstreamConfig(baseUrl()),
		                                        UUID.randomUUID()
		);

		var response = proxyService.proxy(request, Map.of());

		assertAll(
				() -> assertEquals(302, response.statusCode()),
				() -> assertEquals(0, redirectTarget.getRequestCount(),
				                   "redirect target must not be contacted when redirects are disabled"
				)
		);
		redirectTarget.shutdown();
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + mockWebServer.getPort();
	}

	private UpstreamConfig upstreamConfig(String baseUrl) {
		return new UpstreamConfig("test", baseUrl, new SensitiveString("test-key"),
		                          Duration.ofSeconds(5), Duration.ofSeconds(5), "/v1/chat/completions"
		);
	}

	private HttpClient httpClient(Duration connectTimeout, Duration requestTimeout) {
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .connectTimeout(connectTimeout)
		                 .executor(Executors.newVirtualThreadPerTaskExecutor())
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .build();
	}

	private String body() {
		return "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"stream\":true}";
	}

	@Test
	@DisplayName("startup validation passes for a public upstream URL")
	void initPassesForPublicUrl() throws Exception {
		UpstreamConfig config = new UpstreamConfig("test", "https://api.openai.com/v1",
		                                           new SensitiveString("test-key"), Duration.ofSeconds(5),
		                                           Duration.ofSeconds(5), "/v1/chat/completions"
		);
		ProxyService service = new ProxyService(HttpClient.newBuilder().build(), config,
		                                        new SsrfValidator(), new HeaderSanitizer(config)
		);

		service.init();
	}

	@Test
	@DisplayName("startup validation fails closed for a blocked upstream URL")
	void initFailsForBlockedUrl() {
		UpstreamConfig config = new UpstreamConfig("test", "http://127.0.0.1/",
		                                           new SensitiveString("test-key"), Duration.ofSeconds(5),
		                                           Duration.ofSeconds(5), "/v1/chat/completions"
		);
		ProxyService service = new ProxyService(HttpClient.newBuilder().build(), config,
		                                        new SsrfValidator(), new HeaderSanitizer(config)
		);

		assertThrows(SsrfViolationException.class, service::init);
	}

	@Test
	@DisplayName("builds upstream path correctly when baseUrl has a trailing slash")
	void proxiesWithTrailingSlashBaseUrl() throws Exception {
		mockWebServer.enqueue(new MockResponse()
				                      .setResponseCode(200)
				                      .addHeader("Content-Type", "text/event-stream")
				                      .setBody("data: hello\n\n"));

		UpstreamConfig config = upstreamConfig(baseUrl() + "/");
		HttpClient httpClient = httpClient(Duration.ofSeconds(5), Duration.ofSeconds(5));
		SsrfValidator validator = new AllowAllSsrfValidator();
		ProxyService service = new ProxyService(httpClient, config, validator,
		                                        new HeaderSanitizer(config)
		);

		ProxyRequest request = new ProxyRequest(body(), config, UUID.randomUUID());
		HttpResponse<Stream<String>> response = service.proxy(request, Map.of());

		assertEquals(200, response.statusCode());
		assertEquals("/v1/chat/completions", mockWebServer.takeRequest().getPath());
	}
}
