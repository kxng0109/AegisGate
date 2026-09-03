package io.github.kxng0109.aegisgate.ledger;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * The pricing of one model, in the form the cost calculator consumes, including prompt caching rates.
 *
 * <p>This record exists so the catalog can hand out immutable value objects
 * without exposing the JPA entity. Prices are USD per token.</p>
 *
 * @param modelId                     exact model id in the pricing catalog
 * @param provider                    litellm provider name (openai, anthropic, ollama)
 * @param mode                        catalog mode (chat, completion, and so on)
 * @param inputCostPerToken           USD per input token
 * @param outputCostPerToken          USD per output token
 * @param cacheReadInputTokenCost     USD per cache read token, may be {@code null}
 * @param cacheCreationInputTokenCost USD per cache write token, may be {@code null}
 */
public record ModelPricingEntry(
		String modelId,
		String provider,
		String mode,
		BigDecimal inputCostPerToken,
		BigDecimal outputCostPerToken,
		@Nullable BigDecimal cacheReadInputTokenCost,
		@Nullable BigDecimal cacheCreationInputTokenCost
) {

	/**
	 * Convenience constructor maintaining backwards compatibility without cache token rates.
	 *
	 * @param modelId            exact model id in the pricing catalog
	 * @param provider           litellm provider name (openai, anthropic, ollama)
	 * @param mode               catalog mode (chat, completion, and so on)
	 * @param inputCostPerToken  USD per input token
	 * @param outputCostPerToken USD per output token
	 */
	public ModelPricingEntry(
			String modelId,
			String provider,
			String mode,
			BigDecimal inputCostPerToken,
			BigDecimal outputCostPerToken
	) {
		this(modelId, provider, mode, inputCostPerToken, outputCostPerToken, null, null);
	}

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
				entity.getOutputCostPerToken(),
				entity.getCacheReadInputTokenCost(),
				entity.getCacheCreationInputTokenCost()
		);
	}
}