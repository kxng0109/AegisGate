package io.github.kxng0109.aegisgate.ledger;

import java.math.BigDecimal;

/**
 * The pricing of one model, in the form the cost calculator consumes.
 *
 * <p>This record exists so the catalog can hand out immutable value objects
 * without exposing the JPA entity. Prices are USD per token.</p>
 *
 * @param modelId            exact model id in the pricing catalog
 * @param provider           litellm provider name (openai, anthropic, ollama)
 * @param mode               catalog mode (chat, completion, and so on)
 * @param inputCostPerToken  USD per input token
 * @param outputCostPerToken USD per output token
 */
public record ModelPricingEntry(
		String modelId,
		String provider,
		String mode,
		BigDecimal inputCostPerToken,
		BigDecimal outputCostPerToken
) {

	/**
	 * @param entity the persisted row
	 * @return the immutable value form
	 */
	static ModelPricingEntry from(ModelPricingEntity entity) {
		return new ModelPricingEntry(
				entity.getModelId(),
				entity.getProvider(),
				entity.getMode(),
				entity.getInputCostPerToken(),
				entity.getOutputCostPerToken()
		);
	}
}