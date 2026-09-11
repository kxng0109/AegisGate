package io.github.kxng0109.aegisgate.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit trail for budget administration: every limit create, update, and delete records who did what and
 * the before/after snapshots. Spend snapshots are appended (never updated) when a budgeted subject is deleted, so
 * chargeback history survives the subject. Rows are never auto-purged by the application.
 */
@Entity
@Table(name = "budget_audit")
@Getter
public class BudgetAuditRecord {

	@Id
	private UUID id;

	@Column(nullable = false, length = 128)
	private String actor;

	@Column(nullable = false)
	private String action;

	@Column(nullable = false, length = 16)
	private String level;

	@Column(name = "subject_id", nullable = false, length = 128)
	private String subjectId;

	@Column(name = "before_json", columnDefinition = "TEXT")
	private @Nullable String beforeJson;

	@Column(name = "after_json", columnDefinition = "TEXT")
	private @Nullable String afterJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected BudgetAuditRecord() {
	}

	public BudgetAuditRecord(String actor, String action, String level, String subjectId,
	                         @Nullable String beforeJson, @Nullable String afterJson) {
		this.id = UUID.randomUUID();
		this.actor = actor;
		this.action = action;
		this.level = level;
		this.subjectId = subjectId;
		this.beforeJson = beforeJson;
		this.afterJson = afterJson;
	}
}
