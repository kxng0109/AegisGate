package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One opt-in delivery subscription: alerts for {@code scope} at or above {@code minSeverity} go to
 * {@code target} over {@code channel}. No row means no delivery — alert auditing stays always-on regardless.
 * Secrets are never stored: {@code secretRef} names a runtime environment variable resolved at send time.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
public class NotificationPreference {

	@Column(nullable = false, length = 160)
	private String scope;

	@Column(nullable = false, length = 16)
	private String channel;

	@Column(nullable = false, length = 512)
	private String target;

	@Column(name = "secret_ref", length = 128)
	private String secretRef;

	@Column(name = "min_severity", nullable = false, length = 16)
	private String minSeverity = "warning";

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected NotificationPreference() {
	}

	public NotificationPreference(String scope, String channel, String target,
	                              String secretRef, String minSeverity) {
		this.id = UUID.randomUUID();
		this.scope = scope;
		this.channel = channel;
		this.target = target;
		this.secretRef = secretRef;
		this.minSeverity = minSeverity;
	}
}
