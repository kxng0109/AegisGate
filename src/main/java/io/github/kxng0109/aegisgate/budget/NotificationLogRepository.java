package io.github.kxng0109.aegisgate.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link NotificationLogEntry}. Append-only by convention (no update or delete methods are
 * declared); retention is owned by the scheduled janitor, never by application code paths.
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLogEntry, UUID> {
}
