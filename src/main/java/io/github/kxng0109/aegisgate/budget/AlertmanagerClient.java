package io.github.kxng0109.aegisgate.budget;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * POSTs alert batches to the Alertmanager v2 API ({@code POST {base}/api/v2/alerts} with a JSON array body).
 * Retryable failures (429 honoring {@code Retry-After}, 5xx, timeouts) use Full Jitter backoff; 2xx is sent;
 * anything else is terminal. A blank base URL disables POST entirely (log-only mode for environments without
 * Alertmanager; outbox rows stay PENDING as the audit trail).
 */
@Component
public class AlertmanagerClient {

	static final int MAX_ATTEMPTS = 3;

	static final long BACKOFF_BASE_MILLIS = 200L;

	static final long BACKOFF_CAP_MILLIS = 30_000L;

	/** Delivery outcome for one batch POST. */
	public record PostResult(boolean sent, boolean retryable) {
	}

	private static final Logger log = LoggerFactory.getLogger(AlertmanagerClient.class);

	private final BudgetDetectionProperties properties;

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	@Autowired
	public AlertmanagerClient(BudgetDetectionProperties properties) {
		this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				new ObjectMapper());
	}

	AlertmanagerClient(BudgetDetectionProperties properties, HttpClient httpClient,
	                   ObjectMapper objectMapper) {
		this.properties = properties;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * Sends one batch (Alertmanager v2 expects a JSON array even for a single alert).
	 *
	 * @param alerts payloads, each rendered as one array element
	 * @return outcome; log-only mode reports sent (nothing to deliver, nothing lost)
	 */
	public PostResult post(List<Map<String, Object>> alerts) {
		String baseUrl = properties.alertmanagerUrl();
		if (baseUrl == null || baseUrl.isBlank()) {
			log.info("Alertmanager delivery disabled (log-only); {} alert(s) recorded in outbox", alerts.size());
			return new PostResult(true, false);
		}
		String body;
		try {
			body = objectMapper.writeValueAsString(alerts);
		} catch (Exception ex) {
			log.warn("Alert serialization failed; dropping batch");
			return new PostResult(false, false);
		}
		String target = baseUrl.endsWith("/") ? baseUrl + "api/v2/alerts" : baseUrl + "/api/v2/alerts";
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
				                                 .uri(URI.create(target))
				                                 .timeout(Duration.ofSeconds(10))
				                                 .header("Content-Type", "application/json")
				                                 .POST(HttpRequest.BodyPublishers.ofString(body))
				                                 .build();
				HttpResponse<String> response =
						httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				int status = response.statusCode();
				if (status >= 200 && status < 300) {
					return new PostResult(true, false);
				}
				if (status == 429) {
					long wait = retryAfterSeconds(response);
					log.warn("Alertmanager throttled; honoring Retry-After {}s (attempt {}/{})", wait, attempt,
							MAX_ATTEMPTS);
					sleepUninterruptibly(wait * 1000L);
					continue;
				}
				if (status >= 500) {
					log.warn("Alertmanager error {} (attempt {}/{})", status, attempt, MAX_ATTEMPTS);
					sleepUninterruptibly(backoffDelayMillis(attempt));
					continue;
				}
				log.warn("Alertmanager rejected batch with {} (terminal)", status);
				return new PostResult(false, false);
			} catch (IOException ex) {
				log.warn("Alertmanager unreachable (attempt {}/{})", attempt, MAX_ATTEMPTS);
				try {
					sleepUninterruptibly(backoffDelayMillis(attempt));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return new PostResult(false, false);
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return new PostResult(false, false);
			}
		}
		return new PostResult(false, true);
	}

	/**
	 * Full Jitter backoff: {@code random(0, min(cap, base * 2^attempt))}.
	 */
	static long backoffDelayMillis(int attempt) {
		long ceiling = BACKOFF_BASE_MILLIS * (1L << Math.min(attempt, 20));
		ceiling = Math.min(ceiling, BACKOFF_CAP_MILLIS);
		return ThreadLocalRandom.current().nextLong(ceiling + 1);
	}

	private static long retryAfterSeconds(HttpResponse<String> response) {
		try {
			long parsed = Long.parseLong(response.headers()
			                                     .firstValue("Retry-After").orElse("5").trim());
			return Math.min(Math.max(parsed, 1L), 60L);
		} catch (NumberFormatException malformed) {
			return 5L;
		}
	}

	private static void sleepUninterruptibly(long millis) throws InterruptedException {
		if (millis <= 0) {
			return;
		}
		Thread.sleep(millis);
	}
}
