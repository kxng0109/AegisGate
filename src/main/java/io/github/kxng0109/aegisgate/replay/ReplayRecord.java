package io.github.kxng0109.aegisgate.replay;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Durable-tier replay payload: the exact completion re-delivered on idempotent retry. Written post-response
 * (never on the latency path); read only as a re-warm source. The hot tier (Redis, 24h TTL) serves retries.
 */
@Entity
@Table(name = "replay_store")
@Getter
public class ReplayRecord {

	@EmbeddedId
	private ReplayId id;

	@Column(nullable = false)
	private byte[] body;

	@Column(name = "body_hash", nullable = false, length = 64)
	private String bodyHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected ReplayRecord() {
	}

	public ReplayRecord(ReplayId id, byte[] body, String bodyHash) {
		this.id = id;
		this.body = body;
		this.bodyHash = bodyHash;
	}
}
