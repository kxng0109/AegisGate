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
		ModelPricingEntry entry = catalog.lookup(type, model).orElse(null);
		if (entry == null) {
			log.warn(
					"No pricing entry for provider {} model {}; recording zero cost",
					type, model
			);
			return 0;
		}
		BigDecimal inputCost = BigDecimal.valueOf(promptTokens).multiply(entry.inputCostPerToken());
		BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(entry.outputCostPerToken());
		return inputCost.add(outputCost)
		                .multiply(MICRO_DOLLARS_PER_DOLLAR)
		                .setScale(0, RoundingMode.HALF_UP)
		                .longValue();
	}
}