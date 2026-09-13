package io.github.kxng0109.aegisgate.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Hold-then-settle accounting ceilings, bound from {@code gateway.budget.settlement.*}.
 *
 * <p>Admission charges the hold {@code H = prompt + max_tokens × output}; settlement trues it up to actual
 * spend. The kill-switch disables hold/settle calls (admission then charges the prompt-only estimate, the
 * pre-settlement behavior).</p>
 *
 * @param enabled            master kill-switch for hold creation, settlement, and the sweeper
 * @param maxTokensCeiling   server-side ceiling applied when the client omits {@code max_tokens}
 * @param holdTtlSeconds     hold-record TTL; bounds how late a settle may arrive before it becomes a gap
 * @param abortGraceSeconds  output-hold grace after a client abort before the sweeper expires it to a gap
 * @param sweeperBatch       max holds expired per sweeper tick
 */
@ConfigurationProperties("gateway.budget.settlement")
@Validated
public record BudgetSettlementProperties(
		@DefaultValue("true") boolean enabled,
		@Min(1) @Max(1_000_000) @DefaultValue("4096") int maxTokensCeiling,
		@Min(60) @Max(86_400) @DefaultValue("3600") long holdTtlSeconds,
		@Min(5) @Max(600) @DefaultValue("30") long abortGraceSeconds,
		@Min(10) @Max(5_000) @DefaultValue("500") int sweeperBatch
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final BudgetSettlementProperties DEFAULTS =
			new BudgetSettlementProperties(true, 4096, 3600L, 30L, 500);
}
