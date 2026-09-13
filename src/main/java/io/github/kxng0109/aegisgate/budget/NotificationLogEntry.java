package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One delivery attempt record: the send audit trail. Written for every fan-out (sent, failed, skipped for
 * missing prefs never writes — nothing to audit when nobody asked), so operators can prove what left the
 * building and what did not.
 */
@Entity
@Table(name = "notification_log")
@Getter
public class NotificationLogEntry {

	@Column(name = "dedupe_sha", nullable = false, length = 64)
	private String dedupeSha;

	@Column(nullable = false, length = 160)
	private String scope;

	@Column(nullable = false, length = 16)
	private String channel;

	@Column(nullable = false, length = 512)
	private String target;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(nullable = false, length = 512)
	private String detail = "";

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Id
	private UUID id;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected NotificationLogEntry() {
	}

	public NotificationLogEntry(String dedupeSha, String scope, String channel, String target,
	                            String status, String detail) {
		this.id = UUID.randomUUID();
		this.dedupeSha = dedupeSha;
		this.scope = scope;
		this.channel = channel;
		this.target = target;
		this.status = status;
		this.detail = detail;
	}
}
