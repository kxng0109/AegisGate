package io.github.kxng0109.aegisgate.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Background spend-watchdog ceilings, bound from {@code gateway.budget.detection.*}.
 *
 * <p>The detector reads Redis counters once a minute and writes alert decisions to the outbox; the dispatcher
 * POSTs them to Alertmanager. An empty {@code alertmanagerUrl} disables delivery (decisions still recorded in
 * the outbox for audit).</p>
 *
 * @param enabled         master kill-switch for evaluation and dispatch
 * @param alertmanagerUrl Alertmanager v2 base URL (e.g. {@code http://alertmanager:9093}), empty disables POST
 * @param dispatchBatch   max outbox rows claimed per dispatcher tick
 */
@ConfigurationProperties("gateway.budget.detection")
@Validated
public record BudgetDetectionProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("") String alertmanagerUrl,
		@Min(10) @Max(5_000) @DefaultValue("100") int dispatchBatch
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final BudgetDetectionProperties DEFAULTS =
			new BudgetDetectionProperties(true, "", 100);
}
