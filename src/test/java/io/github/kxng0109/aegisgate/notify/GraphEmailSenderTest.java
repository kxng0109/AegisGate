package io.github.kxng0109.aegisgate.notify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Graph mail sender against loopback token and Graph endpoints: disabled without
 * configuration, token caching across sends, sendMail contract, and auth-failure cache invalidation.
 */
@DisplayName("GraphEmailSender")
class GraphEmailSenderTest {

	private record Server(HttpServer http, AtomicInteger tokenHits, AtomicInteger mailHits,
	                      Map<String, List<String>> mailHeaders) {
	}

	private static Server server(int mailCode) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger tokenHits = new AtomicInteger();
		AtomicInteger mailHits = new AtomicInteger();
		Map<String, List<String>> mailHeaders = new ConcurrentHashMap<>();
		http.createContext("/tenant/oauth2/v2.0/token", exchange -> {
			tokenHits.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			byte[] token = "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, token.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(token);
			}
		});
		http.createContext("/v1.0/users/alerts@example.com/sendMail", exchange -> {
			mailHits.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.getRequestHeaders().forEach((name, values) -> mailHeaders.put(name, List.copyOf(values)));
			byte[] empty = new byte[0];
			exchange.sendResponseHeaders(mailCode, empty.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(empty);
			}
		});
		http.start();
		return new Server(http, tokenHits, mailHits, mailHeaders);
	}

	private static GraphEmailProperties properties() {
		return new GraphEmailProperties("tenant", "client", "PATH", "alerts@example.com", 25);
	}

	private static GraphEmailSender sender(Server server) {
		String base = "http://127.0.0.1:" + server.http().getAddress().getPort();
		return new GraphEmailSender(mock(SsrfValidator.class),
				HttpClient.newHttpClient(), new ObjectMapper(),
				properties(), base, base);
	}

	private static NotificationPreference preference() {
		return new NotificationPreference("KEY:hex", "email", "oncall@example.com", null, "warning");
	}

	private static NotificationPayload payload() {
		return new NotificationPayload("KEY:hex", "burn_static", "warning",
				Instant.parse("2026-09-12T14:33:00Z"), "50.00", "2026-09");
	}

	@Test
	@DisplayName("blank configuration disables the channel")
	void blankConfigDisables() {
		GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class),
				GraphEmailProperties.DEFAULTS);

		assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.SKIPPED);
	}

	@Test
	@DisplayName("missing client secret is terminal")
	void missingSecretTerminal() {
		GraphEmailProperties noSecret = new GraphEmailProperties("tenant", "client",
				"AEGIS_TEST_ABSENT_XYZ", "alerts@example.com", 25);
		GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class), noSecret);

		assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);
	}

	@Test
	@DisplayName("sendMail posts with the cached bearer token")
	void sendMailUsesCachedToken() throws Exception {
		Server server = server(202);
		try {
			GraphEmailSender sender = sender(server);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.SENT);
			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.SENT);

			assertThat(server.tokenHits().get()).isEqualTo(1);
			assertThat(server.mailHits().get()).isEqualTo(2);
			String authorization = server.mailHeaders().entrySet().stream()
			                             .filter(entry -> entry.getKey().equalsIgnoreCase("Authorization"))
			                             .map(entry -> entry.getValue().getFirst())
			                             .findFirst()
			                             .orElse("");
			assertThat(authorization).isEqualTo("Bearer tok-1");
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("auth rejection clears the token cache and is terminal")
	void authRejectionClearsCache() throws Exception {
		Server server = server(401);
		try {
			GraphEmailSender sender = sender(server);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);
			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);

			assertThat(server.tokenHits().get()).isEqualTo(2);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("partial configuration disables each blank field")
	void partialConfigDisables() {
		SsrfValidator validator = mock(SsrfValidator.class);
		GraphEmailProperties noClient =
				new GraphEmailProperties("tenant", "", "PATH", "alerts@example.com", 25);
		GraphEmailProperties noMailbox =
				new GraphEmailProperties("tenant", "client", "PATH", "  ", 25);

		assertThat(new GraphEmailSender(validator, noClient).send(preference(), payload()))
				.isEqualTo(ChannelResult.SKIPPED);
		assertThat(new GraphEmailSender(validator, noMailbox).send(preference(), payload()))
				.isEqualTo(ChannelResult.SKIPPED);
	}

	@Test
	@DisplayName("other success codes send")
	void otherSuccessSends() throws Exception {
		Server server = server(200);
		try {
			GraphEmailSender sender = sender(server);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.SENT);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("forbidden clears the cache and is terminal")
	void forbiddenClearsCacheTerminal() throws Exception {
		Server server = server(403);
		try {
			GraphEmailSender sender = sender(server);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);
			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);

			assertThat(server.tokenHits().get()).isEqualTo(2);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("throttled and failed sends are transient")
	void throttledAndFailedTransient() throws Exception {
		Server throttled = server(429);
		Server failed = server(500);
		try {
			assertThat(sender(throttled).send(preference(), payload()))
					.isEqualTo(ChannelResult.TRANSIENT);
			assertThat(sender(failed).send(preference(), payload()))
					.isEqualTo(ChannelResult.TRANSIENT);
		} finally {
			throttled.http().stop(0);
			failed.http().stop(0);
		}
	}

	@Test
	@DisplayName("token endpoint failure degrades to transient")
	void tokenFailureTransient() throws Exception {
		Server server = server(200);
		try {
			HttpClient tokenFailing = mock(HttpClient.class);
			when(tokenFailing.send(any(HttpRequest.class),
					any(HttpResponse.BodyHandler.class)))
					.thenThrow(new IOException("token down"));
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class), tokenFailing,
					new ObjectMapper(), properties(),
					"http://127.0.0.1:" + server.http().getAddress().getPort(),
					"http://127.0.0.1:" + server.http().getAddress().getPort());

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TRANSIENT);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("token transport outcomes degrade to transient")
	@SuppressWarnings("unchecked")
	void tokenTransportOutcomesTransient() throws Exception {
		for (Object failure : new Object[]{
				new java.io.IOException("down"),
				new InterruptedException("interrupted"),
				new RuntimeException("boom")}) {
			HttpClient failing = mock(HttpClient.class);
			if (failure instanceof InterruptedException interrupted) {
				when(failing.send(any(HttpRequest.class),
						any(HttpResponse.BodyHandler.class))).thenThrow(interrupted);
			} else if (failure instanceof java.io.IOException io) {
				when(failing.send(any(HttpRequest.class),
						any(HttpResponse.BodyHandler.class))).thenThrow(io);
			} else {
				when(failing.send(any(HttpRequest.class),
						any(HttpResponse.BodyHandler.class)))
						.thenThrow((RuntimeException) failure);
			}
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class), failing,
					new ObjectMapper(), properties(),
					"http://127.0.0.1:9", "http://127.0.0.1:9");

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TRANSIENT);
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("token responses without a token degrade to transient")
	void tokenWithoutTokenTransient() throws Exception {
		HttpServer http = HttpServer.create(
				new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/tenant/oauth2/v2.0/token", exchange -> {
			exchange.getRequestBody().readAllBytes();
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		http.start();
		try {
			String base = "http://127.0.0.1:" + http.getAddress().getPort();
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class),
					HttpClient.newHttpClient(), new ObjectMapper(),
					properties(), base, base);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TRANSIENT);
		} finally {
			http.stop(0);
		}
	}

	@Test
	@DisplayName("send transport outcomes classify without throwing")
	@SuppressWarnings("unchecked")
	void sendTransportOutcomesClassify() throws Exception {
		Object[][] cases = {
				{new java.io.IOException("down"), ChannelResult.TRANSIENT},
				{new InterruptedException("interrupted"), ChannelResult.TERMINAL},
		};
		for (Object[] kase : cases) {
			HttpClient failing = mock(HttpClient.class);
			HttpResponse<String> tokenResponse = mock(HttpResponse.class);
			when(tokenResponse.statusCode()).thenReturn(200);
			when(tokenResponse.body()).thenReturn(
					"{\"access_token\":\"t\",\"expires_in\":3600}");
			when(failing.send(any(HttpRequest.class),
					any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
				String url = inv.getArgument(0, HttpRequest.class).uri().toString();
				if (url.contains("oauth2")) {
					return tokenResponse;
				}
				Throwable failure = (Throwable) kase[0];
				if (failure instanceof InterruptedException interrupted) {
					throw interrupted;
				}
				if (failure instanceof java.io.IOException io) {
					throw io;
				}
				throw new RuntimeException(failure);
			});
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class), failing,
					new ObjectMapper(), properties(),
					"http://127.0.0.1:9", "http://127.0.0.1:9");

			assertThat(sender.send(preference(), payload())).isEqualTo(kase[1]);
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("serialization failure drops the batch as terminal")
	void serializationFailureDropsBatch() throws Exception {
		ObjectMapper brokenMapper = mock(ObjectMapper.class);
		when(brokenMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
		when(brokenMapper.readTree(anyString())).thenAnswer(inv ->
				new ObjectMapper().readTree((String) inv.getArgument(0)));
		Server server = server(202);
		try {
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class),
					HttpClient.newHttpClient(), brokenMapper, properties(),
					"http://127.0.0.1:" + server.http().getAddress().getPort(),
					"http://127.0.0.1:" + server.http().getAddress().getPort());

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);
		} finally {
			server.http().stop(0);
		}
	}

	@Test
	@DisplayName("empty access token degrades to transient")
	void emptyTokenTransient() throws Exception {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/tenant/oauth2/v2.0/token", exchange -> {
			exchange.getRequestBody().readAllBytes();
			byte[] body = "{\"access_token\":\"\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		http.start();
		try {
			String base = "http://127.0.0.1:" + http.getAddress().getPort();
			GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class),
					HttpClient.newHttpClient(), new ObjectMapper(), properties(),
					base, base);

			assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TRANSIENT);
		} finally {
			http.stop(0);
		}
	}

	@Test
	@DisplayName("interrupted send aborts as terminal")
	void interruptedSendAborts() throws Exception {
		HttpClient interruptedClient = mock(HttpClient.class);
		HttpResponse<String> tokenResponse = mock(HttpResponse.class);
		when(tokenResponse.statusCode()).thenReturn(200);
		when(tokenResponse.body()).thenReturn("{\"access_token\":\"t\",\"expires_in\":3600}");
		when(interruptedClient.send(any(HttpRequest.class),
				any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
			String url = inv.getArgument(0, HttpRequest.class).uri().toString();
			if (url.contains("oauth2")) {
				return tokenResponse;
			}
			throw new InterruptedException("interrupted");
		});
		GraphEmailSender sender = new GraphEmailSender(mock(SsrfValidator.class), interruptedClient,
				new ObjectMapper(), properties(), "http://127.0.0.1:9", "http://127.0.0.1:9");

		assertThat(sender.send(preference(), payload())).isEqualTo(ChannelResult.TERMINAL);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
		Thread.interrupted();
	}
}
