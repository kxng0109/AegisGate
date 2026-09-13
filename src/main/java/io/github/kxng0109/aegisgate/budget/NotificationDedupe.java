package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Per-alert per-channel send guard: at-least-once delivery plus this claim collapses duplicates. Claimed
 * (insert, ignoring unique conflicts) before the first POST; a lost claim means another pod already sent it.
 */
@Entity
@Table(name = "notification_dedupe")
@Getter
public class NotificationDedupe {

	@Column(name = "dedupe_sha", nullable = false, length = 64)
	private String dedupeSha;

	@Column(nullable = false, length = 16)
	private String channel;

	@Column(nullable = false, length = 512)
	private String target;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected NotificationDedupe() {
	}

	public NotificationDedupe(String dedupeSha, String channel, String target) {
		this.id = UUID.randomUUID();
		this.dedupeSha = dedupeSha;
		this.channel = channel;
		this.target = target;
	}
}
