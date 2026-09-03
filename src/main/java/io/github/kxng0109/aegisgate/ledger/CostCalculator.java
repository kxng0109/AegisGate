package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the cost of one completed request from its token counts.
 *
 * <p>All arithmetic is {@link BigDecimal}; money never touches floating
 * point. The result is expressed as micro dollars (one millionth of a US dollar) in a {@code long}, which is how the
 * ledger stores it, and follows the standard formula of input tokens times the input price plus output tokens times the
 * output price. Unknown models cost nothing and log a warning, because an untracked model must never fail a
 * request.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostCalculator {

	/**
	 * Micro dollars per dollar.
	 */
	static final BigDecimal MICRO_DOLLARS_PER_DOLLAR = new BigDecimal("1000000");

	private final ModelPriceCatalog catalog;

	/**
	 * @param type             provider dialect that served the request
	 * @param model            model id reported by the provider
	 * @param promptTokens     input tokens
	 * @param completionTokens output tokens
	 * @return the cost in micro dollars, rounded half up
	 */
	public long calculate(ProviderType type, String model, long promptTokens, long completionTokens) {
		return calculate(type, model, promptTokens, completionTokens, promptTokens, 0L, 0L);
	}

	/**
	 * Computes request cost with prompt caching discounts and write surcharges.
	 *
	 * @param type                 provider dialect
	 * @param model                model id reported by the provider
	 * @param totalPromptTokens    total prompt tokens
	 * @param completionTokens     output tokens
	 * @param uncachedPromptTokens uncached prompt tokens
	 * @param cacheReadTokens      prompt tokens read from cache
	 * @param cacheWriteTokens     prompt tokens written to cache
	 * @return the billed cost in micro dollars, rounded half up
	 */
	public long calculate(
			ProviderType type,
			String model,
			long totalPromptTokens,
			long completionTokens,
			long uncachedPromptTokens,
			long cacheReadTokens,
			long cacheWriteTokens
	) {
		ModelPricingEntry entry = catalog.lookup(type, model).orElse(null);
		if (entry == null) {
			log.warn(
					"No pricing entry for provider {} model {}; recording zero cost",
					type, model
			);
			return 0;
		}

		BigDecimal baseInputRate = entry.inputCostPerToken();
		BigDecimal baseOutputRate = entry.outputCostPerToken();

		BigDecimal uncachedCost = BigDecimal.valueOf(uncachedPromptTokens).multiply(baseInputRate);
		BigDecimal writeCost = BigDecimal.valueOf(cacheWriteTokens)
		                                 .multiply(resolveWriteRate(type, entry, baseInputRate));
		BigDecimal readCost = BigDecimal.valueOf(cacheReadTokens)
		                                .multiply(resolveReadRate(type, entry, baseInputRate));
		BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(baseOutputRate);

		return uncachedCost.add(writeCost)
		                   .add(readCost)
		                   .add(outputCost)
		                   .multiply(MICRO_DOLLARS_PER_DOLLAR)
		                   .setScale(0, RoundingMode.HALF_UP)
		                   .longValue();
	}

	private BigDecimal resolveWriteRate(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheCreationInputTokenCost() != null) {
			return entry.cacheCreationInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(new BigDecimal("1.25"));
		}
		if (type == ProviderType.DEEPSEEK) {
			return BigDecimal.ZERO;
		}
		return baseRate;
	}

	private BigDecimal resolveReadRate(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheReadInputTokenCost() != null) {
			return entry.cacheReadInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(new BigDecimal("0.10"));
		}
		if (type == ProviderType.OPENAI) {
			return baseRate.multiply(new BigDecimal("0.50"));
		}
		if (type == ProviderType.DEEPSEEK) {
			return baseRate.multiply(new BigDecimal("0.10"));
		}
		return baseRate;
	}
}