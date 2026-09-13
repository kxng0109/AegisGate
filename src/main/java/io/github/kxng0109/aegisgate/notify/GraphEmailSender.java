package io.github.kxng0109.aegisgate.notify;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Email delivery via Microsoft Graph {@code sendMail} (OAuth2 client-credentials, application permission
 * {@code Mail.Send}). Token responses are cached until {@code expires_in} minus a safety margin; there are no
 * refresh tokens in this flow. Throughput is capped at ~25 messages/minute per mailbox (the Exchange Online
 * 30/minute hard limit binds first). Disabled without complete configuration.
 */
@Component
public class GraphEmailSender extends BaseSender implements ChannelSender {

	private static final Logger log = LoggerFactory.getLogger(GraphEmailSender.class);

	private final GraphEmailProperties graphProperties;

	private final String loginBase;

	private final String graphBase;

	private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

	@Autowired
	public GraphEmailSender(SsrfValidator ssrfValidator, GraphEmailProperties graphProperties) {
		this(ssrfValidator, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				new ObjectMapper(), graphProperties, "https://login.microsoftonline.com",
				"https://graph.microsoft.com");
	}

	GraphEmailSender(SsrfValidator ssrfValidator, HttpClient httpClient, ObjectMapper objectMapper,
	                 GraphEmailProperties graphProperties, String loginBase, String graphBase) {
		super(ssrfValidator, httpClient, objectMapper);
		this.graphProperties = graphProperties;
		this.loginBase = loginBase;
		this.graphBase = graphBase;
	}

	@Override
	public String channel() {
		return "email";
	}

	@Override
	public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
		if (graphProperties.tenantId().isBlank() || graphProperties.clientId().isBlank()
				|| graphProperties.mailbox().isBlank()) {
			return ChannelResult.SKIPPED;
		}
		String secret = resolveSecret(graphProperties.secretRef());
		if (secret == null) {
			return ChannelResult.TERMINAL;
		}
		String token = accessToken(secret);
		if (token == null) {
			return ChannelResult.TRANSIENT;
		}
		String user = encodePath(graphProperties.mailbox());
		String target = graphBase + "/v1.0/users/" + user + "/sendMail";
		Map<String, Object> message = Map.of(
				"message", Map.of(
						"subject", "AegisGate alert [" + payload.severity() + "] " + payload.detector(),
						"body", Map.of("contentType", "Text", "content", payload.toText()),
						"toRecipients", List.of(Map.of("emailAddress",
								Map.of("address", preference.getTarget())))),
				"saveToSentItems", false);
		String json;
		try {
			json = objectMapper.writeValueAsString(message);
		} catch (Exception ex) {
			return ChannelResult.TERMINAL;
		}
		try {
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(target))
			                                 .timeout(Duration.ofSeconds(10))
			                                 .header("Content-Type", "application/json")
			                                 .header("Authorization", "Bearer " + token)
			                                 .POST(HttpRequest.BodyPublishers.ofString(json))
			                                 .build();
			int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status == 202 || (status >= 200 && status < 300)) {
				return ChannelResult.SENT;
			}
			if (status == 401 || status == 403) {
				tokenCache.clear();
			}
			if (status == 429 || status >= 500) {
				return ChannelResult.TRANSIENT;
			}
			return ChannelResult.TERMINAL;
		} catch (IOException ex) {
			return ChannelResult.TRANSIENT;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ChannelResult.TERMINAL;
		} catch (IllegalArgumentException malformed) {
			return ChannelResult.TERMINAL;
		} catch (RuntimeException ex) {
			log.warn("Graph send failed unexpectedly; treating as transient");
			return ChannelResult.TRANSIENT;
		}
	}

	private @Nullable String accessToken(String secret) {
		CachedToken cached = tokenCache.get("graph");
		if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
			return cached.token();
		}
		String form = "client_id=" + encode(graphProperties.clientId())
				+ "&scope=" + encode("https://graph.microsoft.com/.default")
				+ "&client_secret=" + encode(secret)
				+ "&grant_type=client_credentials";
		try {
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(URI.create(loginBase + "/"
					                                 + encodePath(graphProperties.tenantId())
					                                 + "/oauth2/v2.0/token"))
			                                 .timeout(Duration.ofSeconds(10))
			                                 .header("Content-Type", "application/x-www-form-urlencoded")
			                                 .POST(HttpRequest.BodyPublishers.ofString(form))
			                                 .build();
			HttpResponse<String> response =
					httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("Graph token request failed with {}", response.statusCode());
				return null;
			}
			JsonNode root = objectMapper.readTree(response.body());
			String token = root.path("access_token").asString(null);
			long expiresIn = root.path("expires_in").asLong(3600L);
			if (token == null || token.isEmpty()) {
				return null;
			}
			tokenCache.put("graph", new CachedToken(token,
					Instant.now().plusSeconds(Math.max(60L, expiresIn - 300L))));
			return token;
		} catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
		} catch (IllegalArgumentException malformed) {
			return null;
		} catch (RuntimeException ex) {
			log.warn("Graph token request failed unexpectedly");
			return null;
		}
	}

	private record CachedToken(String token, Instant expiresAt) {
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodePath(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
