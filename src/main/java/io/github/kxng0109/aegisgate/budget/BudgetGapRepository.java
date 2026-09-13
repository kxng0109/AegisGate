package io.github.kxng0109.aegisgate.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link BudgetGapRecord}. Insert-only by convention and by the database trigger; no update or
 * delete methods are declared on purpose.
 */
public interface BudgetGapRepository extends JpaRepository<BudgetGapRecord, UUID> {
}
