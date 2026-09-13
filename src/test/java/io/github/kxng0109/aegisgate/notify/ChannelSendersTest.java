package io.github.kxng0109.aegisgate.notify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Teams/Slack/webhook senders against a loopback server: delivery, terminal classification,
 * SSRF refusal without contact, and HMAC signature verification recomputed independently in the test.
 */
@DisplayName("Channel senders")
class ChannelSendersTest {

	private record Server(HttpServer http, AtomicInteger hits,
	                      Map<String, List<String>> headers, List<byte[]> bodies) {
	}

	private static Server server(int code) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger hits = new AtomicInteger();
		Map<String, List<String>> headers = new ConcurrentHashMap<>();
		List<byte[]> bodies = new ArrayList<>();
		http.createContext("/", exchange -> {
			hits.incrementAndGet();
			exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
			synchronized (bodies) {
				bodies.add(exchange.getRequestBody().readAllBytes());
			}
			byte[] empty = new byte[0];
			exchange.sendResponseHeaders(code, empty.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(empty);
			}
		});
		http.start();
		return new Server(http, hits, headers, bodies);
	}

	private static String url(Server server) {
		return "http://127.0.0.1:" + server.http().getAddress().getPort() + "/hook";
	}

	private static SsrfValidator permissiveValidator() {
		SsrfValidator validator = mock(SsrfValidator.class);
		doNothing().when(validator).validate(any(URI.class));
		return validator;
	}

	private static String header(Map<String, List<String>> headers, String name) {
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue().get(0);
			}
		}
		throw new IllegalStateException("missing header " + name);
	}

	private static NotificationPreference preference(String channel, String target, String secretRef) {
		return new NotificationPreference("KEY:hex", channel, target, secretRef, "warning");
	}

	private static NotificationPayload payload() {
		return new NotificationPayload("KEY:hex", "burn_static", "warning",
				Instant.parse("2026-09-12T14:33:00Z"), "50.00", "2026-09");
	}

	@Test
	@DisplayName("Teams posts the text card")
	void teamsPostsText() throws Exception {
		Server server = server(200);
		try {
			TeamsSender sender = new TeamsSender(permissiveValidator());

			ChannelResult result = sender.send(
					preference("teams", url(server), null), payload());

			assertThat(result).isEqualTo(ChannelResult.SENT);
			String body = new String(server.bodies().get(0), StandardCharsets.UTF_8);
			assertThat(body).contains("burn_static");
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("Slack terminal rejection is not retried")
	void slackTerminalNotRetried() throws Exception {
		Server server = server(404);
		try {
			SlackSender sender = new SlackSender(permissiveValidator());

			ChannelResult result = sender.send(
					preference("slack", url(server), null), payload());

			assertThat(result).isEqualTo(ChannelResult.TERMINAL);
			assertThat(server.hits().get()).isEqualTo(1);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("SSRF-blocked targets are refused without contact")
	void ssrfBlockedRefusedWithoutContact() throws Exception {
		Server server = server(200);
		try {
			TeamsSender sender = new TeamsSender(new SsrfValidator());

			ChannelResult result = sender.send(
					preference("teams", url(server), null), payload());

			assertThat(result).isEqualTo(ChannelResult.TERMINAL);
			assertThat(server.hits().get()).isZero();
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("webhook signs with verifiable HMAC-SHA256")
	void webhookSignsHmac() throws Exception {
		Server server = server(200);
		try {
			WebhookSender sender = new WebhookSender(permissiveValidator());
			String secret = System.getenv("PATH");
			org.junit.jupiter.api.Assumptions.assumeTrue(secret != null && !secret.isEmpty(),
					"needs the PATH environment variable");

			ChannelResult result = sender.send(
					preference("webhook", url(server), "PATH"), payload());

			assertThat(result).isEqualTo(ChannelResult.SENT);
			String signature = header(server.headers(), "X-Aegis-Signature");
			String timestamp = header(server.headers(), "X-Aegis-Timestamp");
			assertThat(signature).matches("t=\\d+,v1=[0-9a-f]{64}");
			long sentAt = Long.parseLong(signature.substring(2, signature.indexOf(',')));
			assertThat(Math.abs(Instant.now().getEpochSecond() - sentAt)).isLessThan(120L);
			assertThat(timestamp).isEqualTo(Long.toString(sentAt));
			byte[] body = server.bodies().get(0);
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(
					(sentAt + "." + new String(body, StandardCharsets.UTF_8))
							.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : expected) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			assertThat(signature).endsWith(hex.toString());
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("webhook without a resolvable secret is terminal without contact")
	void webhookMissingSecretTerminal() throws Exception {
		Server server = server(200);
		try {
			WebhookSender sender = new WebhookSender(permissiveValidator());

			ChannelResult result = sender.send(
					preference("webhook", url(server), "AEGIS_TEST_ABSENT_XYZ"), payload());

			assertThat(result).isEqualTo(ChannelResult.TERMINAL);
			assertThat(server.hits().get()).isZero();
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("webhook with a malformed secret reference is terminal")
	void webhookMalformedRefTerminal() {
		WebhookSender sender = new WebhookSender(permissiveValidator());

		ChannelResult result = sender.send(
				preference("webhook", "http://127.0.0.1:9/hook", "bad ref!"), payload());

		assertThat(result).isEqualTo(ChannelResult.TERMINAL);
	}

	@Test
	@DisplayName("package constructors delegate identically")
	void packageConstructorsDelegate() throws Exception {
		Server server = server(200);
		try {
			java.net.http.HttpClient http = HttpClient.newHttpClient();
			tools.jackson.databind.ObjectMapper mapper = new ObjectMapper();
			TeamsSender teams = new TeamsSender(permissiveValidator(), http, mapper);
			SlackSender slack = new SlackSender(permissiveValidator(), http, mapper);
			WebhookSender webhook = new WebhookSender(permissiveValidator(), http, mapper);

			assertThat(teams.send(preference("teams", url(server), null), payload()))
					.isEqualTo(ChannelResult.SENT);
			assertThat(slack.send(preference("slack", url(server), null), payload()))
					.isEqualTo(ChannelResult.SENT);
			assertThat(webhook.send(preference("webhook", url(server), "PATH"), payload()))
					.isEqualTo(ChannelResult.SENT);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("throttled and failed posts classify without retry")
	void postClassificationWithoutRetry() throws Exception {
		Server throttled = server(429);
		Server failed = server(500);
		try {
			SlackSender sender = new SlackSender(permissiveValidator());

			assertThat(sender.send(preference("slack", url(throttled), null), payload()))
					.isEqualTo(ChannelResult.TRANSIENT);
			assertThat(sender.send(preference("slack", url(failed), null), payload()))
					.isEqualTo(ChannelResult.TRANSIENT);
			assertThat(throttled.hits().get()).isEqualTo(1);
			assertThat(failed.hits().get()).isEqualTo(1);
		} finally {
			throttled.http().stop(0);
			failed.http().stop(0);
		}
	}

	@Test
	@DisplayName("unreachable targets degrade to transient")
	void unreachableDegradesTransient() {
		SlackSender sender = new SlackSender(permissiveValidator());

		ChannelResult result = sender.send(
				preference("slack", "http://127.0.0.1:1/hook", null), payload());

		assertThat(result).isEqualTo(ChannelResult.TRANSIENT);
	}

	@Test
	@DisplayName("malformed targets are terminal without contact")
	void malformedTargetTerminal() {
		SlackSender sender = new SlackSender(permissiveValidator());

		ChannelResult result = sender.send(
				preference("slack", "http://[::1", null), payload());

		assertThat(result).isEqualTo(ChannelResult.TERMINAL);
	}

	@Test
	@DisplayName("serialization failure drops the batch")
	void serializationFailureDropsBatch() {
		ObjectMapper brokenMapper = mock(ObjectMapper.class);
		when(brokenMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
		TeamsSender sender = new TeamsSender(permissiveValidator(),
				HttpClient.newHttpClient(), brokenMapper);

		ChannelResult result = sender.send(
				preference("teams", "https://example.com/hook", null), payload());

		assertThat(result).isEqualTo(ChannelResult.TERMINAL);
	}

	@Test
	@DisplayName("interrupted post aborts as terminal")
	void interruptedPostAborts() throws Exception {
		HttpClient interruptedClient = mock(HttpClient.class);
		when(interruptedClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new InterruptedException("interrupted"));
		SlackSender sender = new SlackSender(permissiveValidator(), interruptedClient,
				new ObjectMapper());

		ChannelResult result = sender.send(
				preference("slack", "https://example.com/hook", null), payload());

		assertThat(result).isEqualTo(ChannelResult.TERMINAL);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
		Thread.interrupted();
	}
}
