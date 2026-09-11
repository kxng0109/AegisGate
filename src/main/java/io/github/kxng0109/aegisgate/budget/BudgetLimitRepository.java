package io.github.kxng0109.aegisgate.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable budget limits. Hot-path enforcement reads the Redis mirror, never this repository.
 */
public interface BudgetLimitRepository extends JpaRepository<BudgetLimit, UUID> {

	/**
	 * @param level     {@code KEY}, {@code TEAM}, or {@code ORG}
	 * @param subjectId key sha256 hex, owner id, or the global scope name
	 * @return the cap for that subject, if any
	 */
	Optional<BudgetLimit> findByLevelAndSubjectId(String level, String subjectId);

	/**
	 * @return every configured cap, for startup backfill into Redis
	 */
	List<BudgetLimit> findAll();
}
