package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One audited settlement gap: spend counted via an admission hold that never settled cleanly to measured usage.
 * The hold H stays in the month counters (safe over-count direction); this row attributes the unattributed part
 * for detectors and chargeback. Insert-only; the database trigger aborts any UPDATE or DELETE.
 */
@Entity
@Table(name = "budget_gap")
@Getter
public class BudgetGapRecord {

	@Column(name = "hold_id", nullable = false, length = 64)
	private String holdId;

	@Column(nullable = false, length = 16)
	private String level;

	@Column(name = "subject_id", nullable = false, length = 128)
	private String subjectId;

	@Column(name = "held_micros", nullable = false)
	private long heldMicros;

	@Column(name = "settled_micros", nullable = false)
	private long settledMicros;

	@Column(name = "orig_month", nullable = false, length = 7)
	private String origMonth;

	@Column(name = "settle_month", nullable = false, length = 7)
	private String settleMonth;

	@Column(nullable = false, length = 16)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected BudgetGapRecord() {
	}

	public BudgetGapRecord(String holdId, String level, String subjectId, long heldMicros, long settledMicros,
	                       String origMonth, String settleMonth, String reason) {
		this.id = UUID.randomUUID();
		this.holdId = holdId;
		this.level = level;
		this.subjectId = subjectId;
		this.heldMicros = heldMicros;
		this.settledMicros = settledMicros;
		this.origMonth = origMonth;
		this.settleMonth = settleMonth;
		this.reason = reason;
	}
}
