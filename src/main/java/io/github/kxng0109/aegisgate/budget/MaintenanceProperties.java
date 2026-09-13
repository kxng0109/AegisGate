package io.github.kxng0109.aegisgate.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retention maintenance ceilings, bound from {@code gateway.maintenance.*}.
 *
 * <p>Policy (all batch-scanned, never blocking): replay partitions older than 90 days detach into standalone
 * archive tables (data preserved, out of the hot path); sent/resolved alerts older than 90 days move to the
 * 1-year archive table; archive rows older than 1 year purge with a loud log line; send logs older than 90
 * days and dedupe claims older than 30 days purge. Audit and gap tables are never touched (WORM by design).</p>
 *
 * @param retentionEnabled master kill-switch for the janitor
 * @param retentionBatch   rows per purge batch
 */
@ConfigurationProperties("gateway.maintenance")
@Validated
public record MaintenanceProperties(
		@DefaultValue("true") boolean retentionEnabled,
		@Min(100) @Max(100_000) @DefaultValue("1000") int retentionBatch
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final MaintenanceProperties DEFAULTS = new MaintenanceProperties(true, 1000);
}
