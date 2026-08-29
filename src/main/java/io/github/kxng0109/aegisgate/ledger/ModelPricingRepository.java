package io.github.kxng0109.aegisgate.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Persists the {@link ModelPricingEntity} catalog.
 */
public interface ModelPricingRepository extends JpaRepository<ModelPricingEntity, String> {

	/**
	 * Inserts or refreshes one pricing row atomically.
	 *
	 * @param modelId                    exact model id
	 * @param provider                   litellm provider name
	 * @param mode                       catalog mode
	 * @param inputCostPerToken          USD per input token
	 * @param outputCostPerToken         USD per output token
	 * @param cacheReadInputTokenCost    USD per cache read token
	 * @param cacheCreationInputTokenCost USD per cache write token
	 * @param maxInputTokens             context window
	 * @param maxOutputTokens            completion bound
	 * @param sourceUrl                  where the prices came from
	 */
	@Modifying
	@Transactional
	@Query(value = """
			INSERT INTO model_pricing
			    (model_id, litellm_provider, mode, input_cost_per_token, output_cost_per_token,
			     cache_read_input_token_cost, cache_creation_input_token_cost,
			     max_input_tokens, max_output_tokens, source_url, updated_at)
			VALUES (:modelId, :provider, :mode, :inputCost, :outputCost,
			        NULLIF(:cacheReadCost, 0)::numeric, NULLIF(:cacheCreationCost, 0)::numeric,
			        NULLIF(:maxInput, 0)::bigint, NULLIF(:maxOutput, 0)::bigint, :sourceUrl, now())
			ON CONFLICT (model_id) DO UPDATE SET
			    litellm_provider = EXCLUDED.litellm_provider,
			    mode = EXCLUDED.mode,
			    input_cost_per_token = EXCLUDED.input_cost_per_token,
			    output_cost_per_token = EXCLUDED.output_cost_per_token,
			    cache_read_input_token_cost = EXCLUDED.cache_read_input_token_cost,
			    cache_creation_input_token_cost = EXCLUDED.cache_creation_input_token_cost,
			    max_input_tokens = EXCLUDED.max_input_tokens,
			    max_output_tokens = EXCLUDED.max_output_tokens,
			    source_url = EXCLUDED.source_url,
			    updated_at = EXCLUDED.updated_at
			""", nativeQuery = true)
	void upsert(
			@Param("modelId") String modelId,
			@Param("provider") String provider,
			@Param("mode") String mode,
			@Param("inputCost") BigDecimal inputCostPerToken,
			@Param("outputCost") BigDecimal outputCostPerToken,
			@Param("cacheReadCost") BigDecimal cacheReadInputTokenCost,
			@Param("cacheCreationCost") BigDecimal cacheCreationInputTokenCost,
			@Param("maxInput") Long maxInputTokens,
			@Param("maxOutput") Long maxOutputTokens,
			@Param("sourceUrl") String sourceUrl
	);
}