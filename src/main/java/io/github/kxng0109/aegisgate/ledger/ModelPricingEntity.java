package io.github.kxng0109.aegisgate.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the pricing catalog: the cost of one model at one provider.
 *
 * <p>Prices are USD per token, kept as fixed point decimals because money must
 * never flow through floating point arithmetic. The table is populated by
 * {@link PricingSyncService} from the LiteLLM catalog on a daily schedule and
 * seeded at migration time, so cost accounting works from the first boot even
 * before any network fetch succeeds.</p>
 */
@Entity
@Table(name = "model_pricing")
@Getter
public class ModelPricingEntity {

	@Id
	@Column(name = "model_id", length = 128)
	private String modelId;

	@Column(name = "litellm_provider", nullable = false, length = 64)
	private String provider;

	@Column(nullable = false, length = 32)
	private String mode;

	@Column(name = "input_cost_per_token", nullable = false, precision = 24, scale = 12)
	private BigDecimal inputCostPerToken;

	@Column(name = "output_cost_per_token", nullable = false, precision = 24, scale = 12)
	private BigDecimal outputCostPerToken;

	@Column(name = "cache_read_input_token_cost", precision = 24, scale = 12)
	private @Nullable BigDecimal cacheReadInputTokenCost;

	@Column(name = "cache_creation_input_token_cost", precision = 24, scale = 12)
	private @Nullable BigDecimal cacheCreationInputTokenCost;

	@Column(name = "max_input_tokens")
	private @Nullable Long maxInputTokens;

	@Column(name = "max_output_tokens")
	private @Nullable Long maxOutputTokens;

	@Column(name = "source_url", nullable = false, length = 512)
	private String sourceUrl;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected ModelPricingEntity() {
	}

	/**
	 * @param modelId                    exact model id in the pricing catalog
	 * @param provider                   litellm provider name (openai, anthropic, ollama)
	 * @param mode                       catalog mode (chat, completion, and so on)
	 * @param inputCostPerToken          USD per input token
	 * @param outputCostPerToken         USD per output token
	 * @param cacheReadInputTokenCost    USD per cache read token, may be {@code null}
	 * @param cacheCreationInputTokenCost USD per cache write token, may be {@code null}
	 * @param maxInputTokens             catalog context window, may be {@code null}
	 * @param maxOutputTokens            catalog completion bound, may be {@code null}
	 * @param sourceUrl                  where the prices were fetched from
	 * @param updatedAt                  when the row was written
	 */
	public ModelPricingEntity(
			String modelId,
			String provider,
			String mode,
			BigDecimal inputCostPerToken,
			BigDecimal outputCostPerToken,
			@Nullable BigDecimal cacheReadInputTokenCost,
			@Nullable BigDecimal cacheCreationInputTokenCost,
			@Nullable Long maxInputTokens,
			@Nullable Long maxOutputTokens,
			String sourceUrl,
			Instant updatedAt
	) {
		this.modelId = modelId;
		this.provider = provider;
		this.mode = mode;
		this.inputCostPerToken = inputCostPerToken;
		this.outputCostPerToken = outputCostPerToken;
		this.cacheReadInputTokenCost = cacheReadInputTokenCost;
		this.cacheCreationInputTokenCost = cacheCreationInputTokenCost;
		this.maxInputTokens = maxInputTokens;
		this.maxOutputTokens = maxOutputTokens;
		this.sourceUrl = sourceUrl;
		this.updatedAt = updatedAt;
	}
}