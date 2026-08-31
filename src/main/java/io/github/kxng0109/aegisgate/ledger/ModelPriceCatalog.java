package io.github.kxng0109.aegisgate.ledger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Read side of the pricing catalog.
 *
 * <p>The full table is small (a few thousand rows at most), so the catalog
 * loads it wholesale into a Caffeine cache with a short expiry and matches in memory. The daily sync refreshes the
 * database; the cache picks the refresh up on its next reload. A miss never fails the request: the cost calculator gets
 * an empty optional and records zero.</p>
 *
 * <p>Matching follows the LiteLLM convention of exact ids with fallbacks:</p>
 * <ol>
 *   <li>the exact model id, preferring a row whose provider matches;</li>
 *   <li>the {@code provider/model} composite key;</li>
 *   <li>the longest registered model id that is a prefix of the reported one,
 *       which covers dated model ids such as {@code claude-sonnet-5-20251001}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ModelPriceCatalog {

	/**
	 * How long a loaded catalog snapshot is kept before it is reloaded.
	 */
	static final Duration SNAPSHOT_TTL = Duration.ofMinutes(15);

	private final ModelPricingRepository repository;
	private final Cache<String, Map<String, List<ModelPricingEntry>>> snapshotCache;

	/**
	 * @param repository the pricing repository
	 */
	public ModelPriceCatalog(ModelPricingRepository repository) {
		this.repository = repository;
		this.snapshotCache = Caffeine.newBuilder()
		                             .maximumSize(1)
		                             .expireAfterWrite(SNAPSHOT_TTL)
		                             .build();
	}

	/**
	 * Looks up the price for a provider dialect and model id.
	 *
	 * @param type  the provider dialect that served the request
	 * @param model the model id reported by that provider
	 * @return the best matching price, or an empty optional when unknown
	 */
	public Optional<ModelPricingEntry> lookup(ProviderType type, String model) {
		if (model == null || model.isBlank()) {
			return Optional.empty();
		}
		Map<String, List<ModelPricingEntry>> byModelId = snapshotCache.get("catalog", key -> load());
		String provider = litellmProvider(type);
		return match(byModelId, provider, model);
	}

	/**
	 * Drops the cached snapshot so the next lookup sees fresh rows. Called after a successful pricing sync.
	 */
	public void invalidate() {
		snapshotCache.invalidateAll();
	}

	private Map<String, List<ModelPricingEntry>> load() {
		Map<String, List<ModelPricingEntry>> byModelId = new LinkedHashMap<>();
		for (ModelPricingEntity entity : repository.findAll()) {
			byModelId.computeIfAbsent(entity.getModelId(), key -> new ArrayList<>())
			         .add(ModelPricingEntry.from(entity));
		}
		log.debug("Loaded {} pricing rows into the catalog", byModelId.size());
		return byModelId;
	}

	private Optional<ModelPricingEntry> match(
			Map<String, List<ModelPricingEntry>> byModelId,
			String provider,
			String model
	) {
		List<ModelPricingEntry> exact = byModelId.get(model);
		if (exact != null && !exact.isEmpty()) {
			Optional<ModelPricingEntry> sameProvider = exact.stream()
			                                                .filter(entry -> provider.equals(entry.provider()))
			                                                .findFirst();
			if (sameProvider.isPresent()) {
				return sameProvider;
			}
			return Optional.of(exact.getFirst());
		}

		List<ModelPricingEntry> composite = byModelId.get(provider + "/" + model);
		if (composite != null && !composite.isEmpty()) {
			return Optional.of(composite.getFirst());
		}

		String longestPrefix = byModelId.keySet().stream()
		                                .filter(model::startsWith)
		                                .max(Comparator.comparingInt(String::length))
		                                .orElse(null);
		if (longestPrefix != null) {
			return Optional.of(byModelId.get(longestPrefix).getFirst());
		}

		return Optional.empty();
	}

	private static String litellmProvider(ProviderType type) {
		return switch (type) {
			case OPENAI -> "openai";
			case ANTHROPIC -> "anthropic";
			case OLLAMA -> "ollama";
		};
	}
}