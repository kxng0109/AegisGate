package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Enterprise FinOps engine computing granular prompt caching cost breakdowns and savings in micro-dollars.
 *
 * <p>Supports explicit provider pricing contracts as well as canonical vendor cache discount multipliers
 * (Anthropic 1.25x write / 0.10x read, OpenAI 0.50x read, DeepSeek 0.10x read) without floating point drift.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinOpsPromptCacheCalculator {

	/**
	 * Micro-dollars per US dollar constant for scaled fixed-point integer conversions.
	 */
	public static final BigDecimal MICRO_DOLLARS_PER_DOLLAR = new BigDecimal("1000000");

	// Canonical vendor multipliers when exact token pricing fields are unspecified
	private static final BigDecimal ANTHROPIC_WRITE_MULTIPLIER = new BigDecimal("1.25");
	private static final BigDecimal ANTHROPIC_READ_MULTIPLIER = new BigDecimal("0.10");
	private static final BigDecimal OPENAI_READ_MULTIPLIER = new BigDecimal("0.50");
	private static final BigDecimal DEEPSEEK_READ_MULTIPLIER = new BigDecimal("0.10");

	private final ModelPriceCatalog catalog;

	/**
	 * Detailed FinOps calculation result in micro-dollars.
	 *
	 * @param listCostMicros      standard list cost without caching discounts
	 * @param effectiveCostMicros effective cost considering contracted rates
	 * @param billedCostMicros    final billed cost taking cache read discounts and write surcharges into account
	 * @param cacheSavingsMicros  total micro-dollars saved from prompt cache hits
	 */
	public record FinOpsCostBreakdown(
			long listCostMicros,
			long effectiveCostMicros,
			long billedCostMicros,
			long cacheSavingsMicros
	) {
		/**
		 * Empty zero-cost breakdown sentinel.
		 */
		public static final FinOpsCostBreakdown ZERO = new FinOpsCostBreakdown(0L, 0L, 0L, 0L);
	}

	/**
	 * Computes the granular FinOps cost breakdown for a request.
	 *
	 * @param type                 provider dialect
	 * @param model                model identifier
	 * @param totalPromptTokens    total prompt tokens reported
	 * @param completionTokens     output tokens reported
	 * @param uncachedPromptTokens uncached prompt tokens
	 * @param cacheReadTokens      prompt tokens read from cache
	 * @param cacheWriteTokens     prompt tokens written to cache
	 * @return calculated breakdown in micro-dollars
	 */
	public FinOpsCostBreakdown calculateBreakdown(
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
			log.warn("No pricing entry for provider {} model {}; recording zero cost", type, model);
			return FinOpsCostBreakdown.ZERO;
		}

		BigDecimal baseInputRate = entry.inputCostPerToken();
		BigDecimal baseOutputRate = entry.outputCostPerToken();

		// 1. Standard list cost without cache optimization
		BigDecimal listInputCost = BigDecimal.valueOf(totalPromptTokens).multiply(baseInputRate);
		BigDecimal listOutputCost = BigDecimal.valueOf(completionTokens).multiply(baseOutputRate);
		long listCostMicros = toMicros(listInputCost.add(listOutputCost));

		// 2. Resolve cache write and read rates
		BigDecimal writeRate = resolveWriteRate(type, entry, baseInputRate);
		BigDecimal readRate = resolveReadRate(type, entry, baseInputRate);

		// 3. Compute granular cached input cost
		BigDecimal uncachedCost = BigDecimal.valueOf(uncachedPromptTokens).multiply(baseInputRate);
		BigDecimal writeCost = BigDecimal.valueOf(cacheWriteTokens).multiply(writeRate);
		BigDecimal readCost = BigDecimal.valueOf(cacheReadTokens).multiply(readRate);
		BigDecimal billedInputCost = uncachedCost.add(writeCost).add(readCost);

		long billedCostMicros = toMicros(billedInputCost.add(listOutputCost));
		long cacheSavingsMicros = Math.max(0L, listCostMicros - billedCostMicros);

		return new FinOpsCostBreakdown(listCostMicros, billedCostMicros, billedCostMicros, cacheSavingsMicros);
	}

	private BigDecimal resolveWriteRate(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheCreationInputTokenCost() != null) {
			return entry.cacheCreationInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(ANTHROPIC_WRITE_MULTIPLIER);
		}
		if (type == ProviderType.DEEPSEEK) {
			return BigDecimal.ZERO; // DeepSeek does not surcharge cache writes
		}
		return baseRate;
	}

	private BigDecimal resolveReadRate(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheReadInputTokenCost() != null) {
			return entry.cacheReadInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(ANTHROPIC_READ_MULTIPLIER);
		}
		if (type == ProviderType.OPENAI) {
			return baseRate.multiply(OPENAI_READ_MULTIPLIER);
		}
		if (type == ProviderType.DEEPSEEK) {
			return baseRate.multiply(DEEPSEEK_READ_MULTIPLIER);
		}
		return baseRate;
	}

	private static long toMicros(BigDecimal dollarAmount) {
		return dollarAmount.multiply(MICRO_DOLLARS_PER_DOLLAR)
		                   .setScale(0, RoundingMode.HALF_UP)
		                   .longValue();
	}
}
