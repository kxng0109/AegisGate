package io.github.kxng0109.aegisgate.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One usage event parked for shared replay when the direct ledger write failed.
 *
 * <p>Every instance drains the same table ({@code SELECT ... FOR UPDATE SKIP LOCKED} hands out disjoint batches),
 * so a record spilled on one pod is replayed by whichever pod claims it — unlike the per-pod spillway file, which
 * remains only as the last resort for a total PostgreSQL outage. {@code request_id} is unique, so redelivered
 * events collapse instead of duplicating, and rows the ledger itself rejects (for example an over-long model id)
 * park as {@code POISONED} with the evidence intact rather than looping forever.</p>
 */
@Entity
@Table(name = "usage_ledger_staging")
@Getter
public class LedgerStagingEntry {

	/** Terminal states: replayed into the ledger, or parked after exhausting retries. */
	public static final String DONE = "DONE";

	/** Parked after exhausting retries; kept for audit, never auto-deleted except by retention purge. */
	public static final String POISONED = "POISONED";

	private static final int MAX_ATTEMPTS = 10;

	@Id
	private UUID id;

	@Column(name = "request_id", nullable = false, unique = true)
	private UUID requestId;

	@Column(name = "owner_id", nullable = false, length = 64)
	private String ownerId;

	@Column(name = "provider", nullable = false, length = 64)
	private String provider;

	@Column(name = "model", nullable = false, columnDefinition = "TEXT")
	private String model;

	@Column(name = "prompt_tokens", nullable = false)
	private long promptTokens;

	@Column(name = "completion_tokens", nullable = false)
	private long completionTokens;

	@Column(name = "total_tokens", nullable = false)
	private long totalTokens;

	@Column(name = "cost_usd_micros", nullable = false)
	private long costUsdMicros;

	@Column(name = "duration_ms", nullable = false)
	private long durationMs;

	@Column(name = "event_time", nullable = false)
	private Instant eventTime;

	@Column(name = "uncached_prompt_tokens", nullable = false)
	private long uncachedPromptTokens;

	@Column(name = "cache_read_tokens", nullable = false)
	private long cacheReadTokens;

	@Column(name = "cache_write_tokens", nullable = false)
	private long cacheWriteTokens;

	@Column(name = "reasoning_tokens", nullable = false)
	private long reasoningTokens;

	@Column(name = "effective_cost_micros", nullable = false)
	private long effectiveCostMicros;

	@Column(name = "billed_cost_micros", nullable = false)
	private long billedCostMicros;

	@Column(name = "request_hash", length = 64)
	private @Nullable String requestHash;

	@Column(name = "status", nullable = false)
	private String status = "PENDING";

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "claimed_by", length = 128)
	private @Nullable String claimedBy;

	@Column(name = "claimed_at")
	private @Nullable Instant claimedAt;

	@Column(name = "next_retry_at", nullable = false)
	private Instant nextRetryAt = Instant.now();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected LedgerStagingEntry() {
	}

	/**
	 * Stages one event for shared replay. Never throws for content reasons: over-wide values park as
	 * {@code POISONED} at drain time with the evidence intact.
	 */
	public static LedgerStagingEntry pendingFrom(TokenUsageEvent event) {
		LedgerStagingEntry row = new LedgerStagingEntry();
		row.id = UUID.randomUUID();
		row.requestId = event.requestId();
		row.ownerId = event.ownerId() == null ? "unknown" : event.ownerId();
		row.provider = event.provider();
		row.model = event.model();
		row.promptTokens = event.promptTokens();
		row.completionTokens = event.completionTokens();
		row.totalTokens = event.totalTokens();
		row.costUsdMicros = event.costUsdMicros();
		row.durationMs = event.durationMs();
		row.eventTime = event.timestamp();
		row.uncachedPromptTokens = event.uncachedPromptTokens();
		row.cacheReadTokens = event.cacheReadTokens();
		row.cacheWriteTokens = event.cacheWriteTokens();
		row.reasoningTokens = event.reasoningTokens();
		row.effectiveCostMicros = event.effectiveCostMicros();
		row.billedCostMicros = event.billedCostMicros();
		row.requestHash = event.requestHash();
		return row;
	}

	/** Maximum drain attempts before a row parks as poisoned. */
	public static int maxAttempts() {
		return MAX_ATTEMPTS;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public void setClaimedBy(@Nullable String claimedBy) {
		this.claimedBy = claimedBy;
	}

	public void setClaimedAt(@Nullable Instant claimedAt) {
		this.claimedAt = claimedAt;
	}

	public void setNextRetryAt(Instant nextRetryAt) {
		this.nextRetryAt = nextRetryAt;
	}
}
