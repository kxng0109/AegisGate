package io.github.kxng0109.aegisgate.budget;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Alertmanager v2 client against a loopback HTTP server: success, throttling with
 * honored {@code Retry-After}, terminal rejection, server-error retry exhaustion, log-only mode, and
 * unreachable-host fail behavior. No external network.
 */
@DisplayName("AlertmanagerClient")
class AlertmanagerClientTest {

	private static final BudgetDetectionProperties ENABLED =
			new BudgetDetectionProperties(true, "http://127.0.0.1:0", 100);

	private record Server(HttpServer http, AtomicInteger hits, List<Integer> codes, String retryAfter,
	                      List<byte[]> bodies) {
	}

	private static Server server(List<Integer> codes, String retryAfter) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger hits = new AtomicInteger();
		List<byte[]> bodies = new ArrayList<>();
		http.createContext("/api/v2/alerts", exchange -> {
			int index = Math.min(hits.getAndIncrement(), codes.size() - 1);
			byte[] requestBody = exchange.getRequestBody().readAllBytes();
			synchronized (bodies) {
				bodies.add(requestBody);
			}
			if (retryAfter != null) {
				exchange.getResponseHeaders().set("Retry-After", retryAfter);
			}
			byte[] empty = new byte[0];
			exchange.sendResponseHeaders(codes.get(index), empty.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(empty);
			}
		});
		http.start();
		return new Server(http, hits, codes, retryAfter, bodies);
	}

	private static BudgetDetectionProperties propertiesFor(Server server) {
		return new BudgetDetectionProperties(true,
				"http://127.0.0.1:" + server.http().getAddress().getPort(), 100);
	}

	private static Map<String, Object> alert() {
		return Map.of(
				"labels", Map.of("alertname", "AegisBudgetBurnStatic", "severity", "warning"),
				"annotations", Map.of("summary", "s"),
				"startsAt", "2026-09-12T14:33:00Z");
	}

	@Test
	@DisplayName("success on first attempt posts the array body")
	void successFirstAttempt() throws Exception {
		Server server = server(List.of(200), null);
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isTrue();
			assertThat(server.hits().get()).isEqualTo(1);
			String body = new String(server.bodies().get(0), StandardCharsets.UTF_8);
			assertThat(body).startsWith("[");
			assertThat(body).contains("AegisBudgetBurnStatic");
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("throttling retries after the honored horizon")
	void throttlingRetries() throws Exception {
		Server server = server(List.of(429, 200), "1");
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isTrue();
			assertThat(server.hits().get()).isEqualTo(2);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("terminal rejection stops immediately")
	void terminalRejectionStops() throws Exception {
		Server server = server(List.of(400), null);
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isFalse();
			assertThat(result.retryable()).isFalse();
			assertThat(server.hits().get()).isEqualTo(1);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("persistent server errors exhaust attempts as retryable")
	void serverErrorsExhaustAsRetryable() throws Exception {
		Server server = server(List.of(500), null);
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isFalse();
			assertThat(result.retryable()).isTrue();
			assertThat(server.hits().get()).isEqualTo(AlertmanagerClient.MAX_ATTEMPTS);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("blank base URL is log-only and reports sent")
	void blankUrlIsLogOnly() {
		AlertmanagerClient client = new AlertmanagerClient(
				new BudgetDetectionProperties(true, "  ", 100));

		AlertmanagerClient.PostResult result = client.post(List.of(alert()));

		assertThat(result.sent()).isTrue();
	}

	@Test
	@DisplayName("null base URL is log-only and reports sent")
	void nullUrlIsLogOnly() {
		AlertmanagerClient client = new AlertmanagerClient(
				new BudgetDetectionProperties(true, null, 100));

		AlertmanagerClient.PostResult result = client.post(List.of(alert()));

		assertThat(result.sent()).isTrue();
	}

	@Test
	@DisplayName("serialization failure drops the batch as terminal")
	void serializationFailureDropsBatch() {
		ObjectMapper brokenMapper = mock(ObjectMapper.class);
		when(brokenMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
		AlertmanagerClient client = new AlertmanagerClient(ENABLED,
				HttpClient.newHttpClient(), brokenMapper);

		AlertmanagerClient.PostResult result = client.post(List.of(alert()));

		assertThat(result.sent()).isFalse();
		assertThat(result.retryable()).isFalse();
	}

	@Test
	@DisplayName("trailing-slash base URL still targets the alerts path")
	void trailingSlashUrlTargetsAlertsPath() throws Exception {
		Server server = server(List.of(200), null);
		try {
			BudgetDetectionProperties slashed = new BudgetDetectionProperties(true,
					"http://127.0.0.1:" + server.http().getAddress().getPort() + "/", 100);
			AlertmanagerClient client = new AlertmanagerClient(slashed);

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isTrue();
			assertThat(server.hits().get()).isEqualTo(1);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("unreachable host exhausts attempts as retryable")
	void unreachableHostExhaustsAsRetryable() {
		AlertmanagerClient client = new AlertmanagerClient(
				new BudgetDetectionProperties(true, "http://127.0.0.1:1", 100));

		AlertmanagerClient.PostResult result = client.post(List.of(alert()));

		assertThat(result.sent()).isFalse();
		assertThat(result.retryable()).isTrue();
	}

	@Test
	@DisplayName("malformed Retry-After falls back to the default horizon")
	void malformedRetryAfterFallsBack() throws Exception {
		Server server = server(List.of(429, 200), "soon");
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isTrue();
			assertThat(server.hits().get()).isEqualTo(2);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("redirects are terminal without following")
	void redirectTerminal() throws Exception {
		Server server = server(List.of(301), null);
		try {
			AlertmanagerClient client = new AlertmanagerClient(propertiesFor(server));

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isFalse();
			assertThat(result.retryable()).isFalse();
			assertThat(server.hits().get()).isEqualTo(1);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("missing Retry-After defaults to five seconds")
	void missingRetryAfterDefaults() throws Exception {
		Server noHeader = null;
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger hits = new AtomicInteger();
		http.createContext("/api/v2/alerts", exchange -> {
			int index = hits.getAndIncrement();
			byte[] empty = new byte[0];
			exchange.sendResponseHeaders(index == 0 ? 429 : 200, empty.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(empty);
			}
		});
		http.start();
		try {
			BudgetDetectionProperties props = new BudgetDetectionProperties(true,
					"http://127.0.0.1:" + http.getAddress().getPort(), 100);
			AlertmanagerClient client = new AlertmanagerClient(props);

			AlertmanagerClient.PostResult result = client.post(List.of(alert()));

			assertThat(result.sent()).isTrue();
			assertThat(hits.get()).isEqualTo(2);
		} finally {
			http.stop(0);
		}
	}

	@Test
	@DisplayName("interrupt during backoff aborts as non-retryable")
	void interruptAbortsBackoff() throws Exception {
		HttpClient failingClient = mock(HttpClient.class);
		when(failingClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new IOException("conn reset"));
		AlertmanagerClient client = new AlertmanagerClient(ENABLED, failingClient, new ObjectMapper());
		AtomicInteger done = new AtomicInteger();
		AtomicInteger interrupted = new AtomicInteger();

		Thread worker = new Thread(() -> {
			AlertmanagerClient.PostResult result = client.post(List.of(alert()));
			if (!result.sent() && !result.retryable()) {
				done.incrementAndGet();
			}
			if (Thread.currentThread().isInterrupted()) {
				interrupted.incrementAndGet();
			}
			Thread.interrupted();
		});
		// Pre-interrupting makes the first backoff sleep throw deterministically (no timing involved).
		worker.interrupt();
		worker.start();
		worker.join(10_000);

		assertThat(done.get()).isEqualTo(1);
		assertThat(interrupted.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("backoff delays stay within the cap")
	void backoffBounded() {
		for (int attempt = 1; attempt <= 25; attempt++) {
			assertThat(AlertmanagerClient.backoffDelayMillis(attempt))
					.isBetween(0L, AlertmanagerClient.BACKOFF_CAP_MILLIS);
		}
	}

	@Test
	@DisplayName("interrupted send aborts as non-retryable")
	void interruptedSendAborts() throws Exception {
		java.net.http.HttpClient interruptedClient = mock(java.net.http.HttpClient.class);
		when(interruptedClient.send(any(java.net.http.HttpRequest.class),
				any(java.net.http.HttpResponse.BodyHandler.class)))
				.thenThrow(new InterruptedException("interrupted"));
		AlertmanagerClient client = new AlertmanagerClient(ENABLED, interruptedClient,
				new tools.jackson.databind.ObjectMapper());

		AlertmanagerClient.PostResult result = client.post(List.of(alert()));

		assertThat(result.sent()).isFalse();
		assertThat(result.retryable()).isFalse();
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
		Thread.interrupted();
	}
}
