package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.proxy.protocol.AnthropicAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.OllamaAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiPassthroughAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapterResolver;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProviderClientAdapter} against an in process
 * {@link MockWebServer}: request shape, headers, key handling, the model
 * override rewrite, and response streaming.
 */
@DisplayName("ProviderClientAdapter")
class ProviderClientAdapterTest {

	private MockWebServer server;
	private ProviderClientAdapter adapter;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		ObjectMapper mapper = new ObjectMapper();
		ProtocolAdapterResolver resolver = new ProtocolAdapterResolver(
				new OpenAiPassthroughAdapter(mapper),
				new AnthropicAdapter(mapper),
				new OllamaAdapter(mapper)
		);
		adapter = new ProviderClientAdapter(httpClient(), resolver);
	}

	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}

	@Test
	@DisplayName("sends a POST with JSON body, content type and bearer auth")
	void sendsPostWithAuth() throws Exception {
		server.enqueue(sseResponse("data: [DONE]"));
		ProviderConfig config = providerConfig(server, "sk-test", Duration.ofSeconds(5));

		adapter.sendAsync(config, "{\"model\":\"gpt-x\"}", null).join();

		RecordedRequest recorded = server.takeRequest();
		assertEquals("POST", recorded.getMethod());
		assertEquals("/v1/chat/completions", recorded.getPath());
		assertEquals("application/json", recorded.getHeader("Content-Type"));
		assertEquals("Bearer sk-test", recorded.getHeader("Authorization"));
		JsonNode body = new ObjectMapper().readTree(recorded.getBody().readUtf8());
		assertEquals("gpt-x", body.get("model").asText());
		assertEquals(true, body.path("stream_options").path("include_usage").asBoolean(),
				"the passthrough must always ask the upstream for usage");
	}

	@Test
	@DisplayName("omits the Authorization header when the provider has no key")
	void omitsAuthForKeylessProvider() throws Exception {
		server.enqueue(sseResponse("data: [DONE]"));
		ProviderConfig config = providerConfigWithKey(server, "", Duration.ofSeconds(5));

		adapter.sendAsync(config, "{\"model\":\"gpt-x\"}", null).join();

		assertNull(server.takeRequest().getHeader("Authorization"));
	}

	@Test
	@DisplayName("rewrites the model in the body when an override is configured")
	void appliesModelOverride() throws Exception {
		server.enqueue(sseResponse("data: [DONE]"));
		ProviderConfig config = providerConfig(server, "sk-test", Duration.ofSeconds(5));

		adapter.sendAsync(config, "{\"model\":\"gpt-x\",\"messages\":[]}", "llama3").join();

		RecordedRequest recorded = server.takeRequest();
		JsonNode body = new ObjectMapper().readTree(recorded.getBody().readUtf8());
		assertEquals("llama3", body.get("model").asText());
	}

	@Test
	@DisplayName("streams the response body as lines")
	void streamsResponseLines() throws Exception {
		server.enqueue(sseResponse("data: {\"a\":1}\n\ndata: [DONE]"));
		ProviderConfig config = providerConfig(server, "sk-test", Duration.ofSeconds(5));

		HttpResponse<Stream<String>> response =
				adapter.sendAsync(config, "{\"model\":\"gpt-x\"}", null).join();

		assertEquals(200, response.statusCode());
		String streamed = response.body().collect(Collectors.joining("\n"));
		assertTrue(streamed.contains("data: {\"a\":1}"));
		assertTrue(streamed.contains("data: [DONE]"));
	}

	@Test
	@DisplayName("a base URL with a trailing slash still builds the right path")
	void baseUrlWithTrailingSlash() throws Exception {
		server.enqueue(sseResponse("data: [DONE]"));
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create(server.url("/").toString()),
				new SensitiveString("sk-test"), Duration.ofSeconds(3), Duration.ofSeconds(5)
		);

		adapter.sendAsync(config, "{\"model\":\"gpt-x\"}", null).join();

		assertEquals("/v1/chat/completions", server.takeRequest().getPath());
	}

	private static ProviderConfig providerConfig(MockWebServer server, String key, Duration timeout) {
		return providerConfigWithKey(server, key, timeout);
	}

	private static ProviderConfig providerConfigWithKey(MockWebServer server, String key, Duration timeout) {
		return new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create(server.url("/").toString()),
				new SensitiveString(key), Duration.ofSeconds(3), timeout
		);
	}

	private static MockResponse sseResponse(String body) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "text/event-stream")
				.setBody(body);
	}

	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .connectTimeout(Duration.ofSeconds(5))
		                 .executor(Executors.newVirtualThreadPerTaskExecutor())
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .build();
	}
}