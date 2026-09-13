package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One detector decision awaiting (or finished with) Alertmanager delivery. Written transactionally by the
 * detector; claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED} by the dispatcher, so N pods never
 * double-send. The {@code dedupe_sha} unique constraint collapses repeat evaluations to one alert.
 */
@Entity
@Table(name = "alert_events")
@Getter
public class AlertEvent {

	@Column(name = "dedupe_sha", nullable = false, length = 64, unique = true)
	private String dedupeSha;

	@Column(nullable = false, length = 160)
	private String scope;

	@Column(nullable = false, length = 64)
	private String detector;

	@Column(nullable = false, length = 16)
	private String severity;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Column(nullable = false)
	private String payload;

	@Column(name = "value_text", length = 32)
	private String valueText;

	@Column(length = 7)
	private String month;

	@Column(nullable = false, length = 16)
	private String status = "PENDING";

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_retry_at", nullable = false)
	private Instant nextRetryAt = Instant.now();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected AlertEvent() {
	}

	public AlertEvent(String dedupeSha, String scope, String detector, String severity,
	                  Instant startsAt, String payload, String valueText, String month) {
		this.id = UUID.randomUUID();
		this.dedupeSha = dedupeSha;
		this.scope = scope;
		this.detector = detector;
		this.severity = severity;
		this.startsAt = startsAt;
		this.payload = payload;
		this.valueText = valueText;
		this.month = month;
	}

	public void markSent() {
		this.status = "SENT";
	}

	public void markResolved() {
		this.status = "RESOLVED";
	}

	/**
	 * Schedules another attempt, keeping the row claimable. After too many attempts the dispatcher marks it
	 * dead instead (poison alert, kept for audit).
	 */
	public void backoff(Instant nextRetryAt) {
		this.attempts = this.attempts + 1;
		this.nextRetryAt = nextRetryAt;
	}

	public void markDead() {
		this.status = "FAILED";
	}
}
