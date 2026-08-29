package io.github.kxng0109.aegisgate.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Refreshes the pricing catalog from the LiteLLM model pricing file.
 *
 * <p>The gateway records cost against whatever the catalog holds, so the
 * prices must stay current as providers change them. The sync fetches the LiteLLM catalog (configurable URL, so it can
 * be pinned to a tag or commit), keeps the chat oriented entries, and upserts them into the pricing table. It runs once
 * at startup and then on a daily cron schedule.</p>
 *
 * <p>The sync is strictly best effort. A failed fetch, an unparseable file,
 * or a database outage only logs a warning and leaves the previous rows in place; the hot path never depends on
 * it.</p>
 */
@Slf4j
@Component
public class PricingSyncService {

	/**
	 * Bound for one catalog fetch.
	 */
	static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final ModelPricingRepository repository;
	private final ModelPriceCatalog priceCatalog;
	private final String sourceUrl;

	/**
	 * @param httpClient   shared upstream client
	 * @param objectMapper Jackson mapper for the catalog file
	 * @param repository   pricing repository
	 * @param priceCatalog read side of the catalog, invalidated after a sync
	 * @param sourceUrl    where the catalog is fetched from
	 */
	public PricingSyncService(
			HttpClient httpClient,
			ObjectMapper objectMapper,
			ModelPricingRepository repository,
			ModelPriceCatalog priceCatalog,
			@Value("${gateway.pricing.source-url:https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json}") String sourceUrl
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.repository = repository;
		this.priceCatalog = priceCatalog;
		this.sourceUrl = sourceUrl;
	}

	/**
	 * Best effort sync shortly after the application is ready.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void syncOnReady() {
		refresh();
	}

	/**
	 * Daily refresh of the pricing catalog.
	 */
	@Scheduled(cron = "${gateway.pricing.refresh-cron:0 0 3 * * *}")
	public void syncOnSchedule() {
		refresh();
	}

	/**
	 * Fetches and stores the catalog. Never throws; failures are logged.
	 */
	public void refresh() {
		try {
			JsonNode root = fetchCatalog();
			int kept = upsert(root);
			priceCatalog.invalidate();
			log.info("Refreshed pricing catalog from {}: kept {} entries", sourceUrl, kept);
		} catch (Exception ex) {
			log.warn(
					"Could not refresh the pricing catalog from {}: {}",
					sourceUrl, ex.getMessage()
			);
		}
	}

	@Transactional
	int upsert(JsonNode root) {
		int kept = 0;
		for (Map.Entry<String, JsonNode> field : root.properties()) {
			JsonNode entry = field.getValue();
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String provider = entry.path("litellm_provider").asText("");
			String mode = entry.path("mode").asText("");
			if (!"chat".equals(mode) && !"ollama".equals(provider)) {
				continue;
			}
			BigDecimal inputCost = decimalOrDefault(entry, "input_cost_per_token");
			BigDecimal outputCost = decimalOrDefault(entry, "output_cost_per_token");
			repository.upsert(
					field.getKey(),
					provider.isBlank() ? "unknown" : provider,
					mode.isBlank() ? "chat" : mode,
					inputCost,
					outputCost,
					decimalOrDefault(entry, "cache_read_input_token_cost"),
					decimalOrDefault(entry, "cache_creation_input_token_cost"),
					longOrDefault(entry, "max_input_tokens"),
					longOrDefault(entry, "max_output_tokens"),
					sourceUrl
			);
			kept++;
		}
		return kept;
	}

	private JsonNode fetchCatalog() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
		                                 .timeout(FETCH_TIMEOUT)
		                                 .GET()
		                                 .build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("catalog fetch returned HTTP " + response.statusCode());
		}
		return objectMapper.readTree(response.body());
	}

	/**
	 * The catalog values are nullable; the repository binds non null parameters and translates zero back to SQL NULL
	 * for the optional columns.
	 */
	private static BigDecimal decimalOrDefault(JsonNode entry, String field) {
		JsonNode node = entry.get(field);
		return node != null && node.isNumber() ? node.decimalValue() : BigDecimal.ZERO;
	}

	private static long longOrDefault(JsonNode entry, String field) {
		JsonNode node = entry.get(field);
		if (node == null || !node.isIntegralNumber()) {
			return 0;
		}
		return Math.max(0, node.asLong());
	}
}