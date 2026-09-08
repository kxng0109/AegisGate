package io.github.kxng0109.aegisgate.ledger;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded executor ceilings for the async ledger path, bound from {@code gateway.ledger.executor.*}.
 *
 * <p>Database writes must stay rate-limited and bounded no matter the ingress rate: a dedicated pool
 * with a fixed size and a bounded queue keeps bursts from piling unbounded work onto the ledger. These values are read
 * once at startup when the {@code ledgerExecutor} bean is created.</p>
 *
 * @param corePoolSize            thread pool size of the ledger executor
 * @param maxPoolSize             maximum thread pool size of the ledger executor
 * @param queueCapacity           bounded queue capacity of the ledger executor
 * @param awaitTerminationSeconds graceful shutdown drain timeout in seconds
 */
@ConfigurationProperties("gateway.ledger.executor")
@Validated
public record LedgerExecutorProperties(
		@Min(1) @Max(64) @DefaultValue("2") int corePoolSize,
		@Min(1) @Max(64) @DefaultValue("4") int maxPoolSize,
		@Min(100) @Max(1_000_000) @DefaultValue("1000") int queueCapacity,
		@Min(1) @Max(300) @DefaultValue("10") int awaitTerminationSeconds
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final LedgerExecutorProperties DEFAULTS = new LedgerExecutorProperties(2, 4, 1_000, 10);
}
