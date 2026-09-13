package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Hard-bounced destination: the fan-out skips it without attempting delivery. Cleared only by explicit
 * operator action (delete the row), never automatically — a flapping destination must not silently resume.
 */
@Entity
@Table(name = "notification_bounces")
@Getter
public class NotificationBounce {

	@Column(nullable = false, length = 16)
	private String channel;

	@Column(nullable = false, length = 512)
	private String target;

	@Column(nullable = false, length = 256)
	private String reason = "";

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected NotificationBounce() {
	}

	public NotificationBounce(String channel, String target, String reason) {
		this.id = UUID.randomUUID();
		this.channel = channel;
		this.target = target;
		this.reason = reason;
	}
}
