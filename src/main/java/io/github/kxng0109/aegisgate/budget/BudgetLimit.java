package io.github.kxng0109.aegisgate.budget;

import jakarta.persistence.*;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable source of truth for one hard spend cap. The hot path never reads this table: limits are mirrored to Redis
 * config hashes write-through by admin CRUD (and backfilled at startup), so enforcement stays at one Lua round trip
 * with zero per-request database reads.
 *
 * <p>Levels: {@code KEY} (subject = key sha256 hex), {@code TEAM} (subject = owner id), {@code ORG} (subject =
 * the single global scope). Subject ids are restricted to {@code [a-z0-9-]} at the API boundary so they can never break
 * Redis key structure. A zero micro-limit disables that window. Optimistic locking ({@code version}) makes concurrent
 * admin edits fail loudly instead of silently overwriting.</p>
 */
@Entity
@Table(name = "budget_limits")
@Getter
public class BudgetLimit {

	@Column(nullable = false, length = 16)
	private String level;

	@Column(name = "subject_id", nullable = false, length = 128)
	private String subjectId;

	@Column(name = "minute_micros", nullable = false)
	private long minuteMicros;

	@Column(name = "month_micros", nullable = false)
	private long monthMicros;

	@Column(name = "webhook_url", length = 512)
	private @Nullable String webhookUrl;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected BudgetLimit() {
	}

	public BudgetLimit(String level, String subjectId, long minuteMicros, long monthMicros,
	                   @Nullable String webhookUrl) {
		this.id = UUID.randomUUID();
		this.level = level;
		this.subjectId = subjectId;
		this.minuteMicros = minuteMicros;
		this.monthMicros = monthMicros;
		this.webhookUrl = webhookUrl;
	}

	public void update(long minuteMicros, long monthMicros, @Nullable String webhookUrl) {
		this.minuteMicros = minuteMicros;
		this.monthMicros = monthMicros;
		this.webhookUrl = webhookUrl;
		this.updatedAt = Instant.now();
	}
}
