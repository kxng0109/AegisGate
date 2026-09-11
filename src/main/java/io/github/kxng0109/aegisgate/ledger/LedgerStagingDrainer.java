package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Drains the shared dead-letter staging table into the usage ledger.
 *
 * <p>Every instance polls; {@code FOR UPDATE SKIP LOCKED} claims hand out disjoint batches, so any pod replays any
 * spilled row exactly once per claim. Transactions are deliberately small: the claim runs in one transaction and
 * every row drains in its own, so one poison row can never roll back its batch-mates. A crashed consumer's rows age
 * back to claimable via the janitor threshold; rows that exhaust retries park as {@code POISONED} with evidence
 * intact; terminal rows older than the retention window are purged daily.</p>
 */
@Component
@ConditionalOnProperty(
		prefix = "gateway.ledger.staging-drain",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class LedgerStagingDrainer {

	static final int CLAIM_BATCH_SIZE = 500;

	static final Duration STALE_CLAIM_AGE = Duration.ofMinutes(5);

	static final Duration RETENTION = Duration.ofDays(30);

	static final long MAX_BACKOFF_SECONDS = 3_600L;

	private final LedgerStagingRepository staging;

	private final UsageLedgerRepository ledger;

	private final MeterRegistry meterRegistry;

	private final TransactionTemplate requiresNew;

	private final String podId;

	public LedgerStagingDrainer(
			LedgerStagingRepository staging,
			UsageLedgerRepository ledger,
			PlatformTransactionManager transactionManager,
			@Nullable MeterRegistry meterRegistry
	) {
		this.staging = staging;
		this.ledger = ledger;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
		this.requiresNew = new TransactionTemplate(transactionManager);
		this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
		String hostname = System.getenv("HOSTNAME");
		this.podId = hostname != null && !hostname.isBlank()
				? hostname
				: "pod-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * Claims one batch and drains it row by row, then reclaims crashed consumers' rows. Runs every few seconds;
	 * idle polls are a single tiny partial-index scan.
	 *
	 * <p>Transaction boundaries are explicit ({@link TransactionTemplate}), never declarative: the claim commits
	 * before any row is processed, so per-row transactions always observe claimed state. This method behaves
	 * identically whether invoked by the scheduler proxy or directly, which is also what makes it unit-testable
	 * without a Spring proxy.</p>
	 */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
	public void drain() {
		requiresNew.executeWithoutResult(ignored ->
				staging.resetStaleClaims(Instant.now().minus(STALE_CLAIM_AGE)));
		List<UUID> claimed = requiresNew.execute(status ->
				staging.claimBatch(podId, CLAIM_BATCH_SIZE).stream()
				      .map(LedgerStagingEntry::getId)
				      .toList());
		if (claimed == null) {
			return;
		}
		for (UUID rowId : claimed) {
			drainOne(rowId);
		}
	}

	/**
	 * Purges terminal rows past the retention window. Daily; the partial poll index keeps the scan cheap.
	 */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
	public void purge() {
		requiresNew.executeWithoutResult(ignored ->
				staging.purgeCompleted(Instant.now().minus(RETENTION)));
	}

	private enum DrainOutcome {
		SKIP,
		REPLAYED,
		CONFLICT,
		FAILED
	}

	private void drainOne(UUID rowId) {
		DrainOutcome outcome = requiresNew.execute(status -> {
			try {
				LedgerStagingEntry row = staging.findById(rowId).orElse(null);
				if (row == null || !"CLAIMED".equals(row.getStatus()) || !podId.equals(row.getClaimedBy())) {
					return DrainOutcome.SKIP;
				}
				if (!ledger.existsByRequestId(row.getRequestId())) {
					ledger.save(toLedgerEntry(row));
					// Force the INSERT now: without an explicit flush a constraint
					// violation would surface at commit time, outside this handler,
					// and a poison row would escape as an error instead of parking.
					ledger.flush();
				}
				return DrainOutcome.REPLAYED;
			} catch (org.springframework.dao.DataIntegrityViolationException conflict) {
				// The transaction is poisoned past this point: PostgreSQL aborts the
				// whole tx on any statement error, so roll back and resolve below in
				// a fresh transaction instead of writing bookkeeping here.
				status.setRollbackOnly();
				return DrainOutcome.CONFLICT;
			} catch (RuntimeException ex) {
				status.setRollbackOnly();
				return DrainOutcome.FAILED;
			}
		});
		if (outcome == null || outcome == DrainOutcome.SKIP) {
			return;
		}
		requiresNew.executeWithoutResult(status -> {
			LedgerStagingEntry row = staging.findById(rowId).orElse(null);
			if (row == null) {
				return;
			}
			if (outcome == DrainOutcome.REPLAYED) {
				row.setStatus(LedgerStagingEntry.DONE);
				staging.save(row);
				recordOutcome(row.getProvider(), "replayed");
				return;
			}
			if (ledger.existsByRequestId(row.getRequestId())) {
				row.setStatus(LedgerStagingEntry.DONE);
				staging.save(row);
				recordOutcome(row.getProvider(), "duplicate");
				return;
			}
			parkAsFailed(row, "failed");
		});
	}

	/**
	 * Parks a failed row for retry with exponential backoff, or as poisoned once retries are exhausted.
	 * Poisoned rows keep their evidence and are never auto-deleted except by the retention purge.
	 */
	private void parkAsFailed(LedgerStagingEntry row, String outcome) {
		int attempts = row.getAttempts() + 1;
		row.setAttempts(attempts);
		if (attempts > LedgerStagingEntry.maxAttempts()) {
			row.setStatus(LedgerStagingEntry.POISONED);
		} else {
			long backoff = Math.min(1L << Math.min(attempts, 12), MAX_BACKOFF_SECONDS);
			row.setStatus("PENDING");
			row.setNextRetryAt(Instant.now().plusSeconds(backoff));
		}
		staging.save(row);
		recordOutcome(row.getProvider(), outcome);
	}

	private static UsageLedgerEntry toLedgerEntry(LedgerStagingEntry row) {		return new UsageLedgerEntry(
				row.getRequestId(),
				row.getOwnerId(),
				row.getProvider(),
				row.getModel(),
				safeInt(row.getPromptTokens()),
				safeInt(row.getCompletionTokens()),
				safeInt(row.getTotalTokens()),
				row.getCostUsdMicros(),
				row.getDurationMs(),
				row.getEventTime(),
				safeInt(row.getUncachedPromptTokens()),
				safeInt(row.getCacheReadTokens()),
				safeInt(row.getCacheWriteTokens()),
				safeInt(row.getReasoningTokens()),
				row.getEffectiveCostMicros(),
				row.getBilledCostMicros(),
				row.getRequestHash()
		);
	}

	private static int safeInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}

	private void recordOutcome(String provider, String outcome) {		try {
			Counter.builder("aegis.ledger.staging.drained")
			       .tag("provider", provider == null || provider.isBlank() ? "unknown" : provider)
			       .tag("outcome", outcome)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}
}
