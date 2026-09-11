package io.github.kxng0109.aegisgate.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Append-only budget administration audit trail. Written on every limit mutation; never updated or deleted by the
 * application.
 */
public interface BudgetAuditRepository extends JpaRepository<BudgetAuditRecord, UUID> {
}
