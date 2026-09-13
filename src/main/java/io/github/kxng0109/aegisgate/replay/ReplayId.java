package io.github.kxng0109.aegisgate.replay;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite primary key for {@link ReplayRecord}: the replay id plus the expiry instant (the partition key —
 * PostgreSQL requires it in every unique constraint on a partitioned table).
 */
@Embeddable
public record ReplayId(UUID id, @Column(name = "expires_at") Instant expiresAt) implements Serializable {
}
