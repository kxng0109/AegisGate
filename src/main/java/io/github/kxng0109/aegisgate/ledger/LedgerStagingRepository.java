package io.github.kxng0109.aegisgate.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared dead-letter staging: every instance claims disjoint batches, so spilled
 * usage survives pod death and replays exactly once per row.
 */
public interface LedgerStagingRepository extends JpaRepository<LedgerStagingEntry, UUID> {

	/**
	 * Atomically claims up to {@code limit} due rows for one pod. {@code FOR UPDATE SKIP LOCKED} hands concurrent
	 * consumers disjoint sets without waiting: a row locked by another transaction is skipped, never double-claimed.
	 * Attempts increment at claim time so a crashed consumer's rows age into the janitor threshold below.
	 *
	 * @param pod   claiming pod identity (debugging only, never correctness)
	 * @param limit maximum rows to claim
	 * @return the claimed rows, oldest-due first
	 */
	@Modifying
	@Query(value = "UPDATE usage_ledger_staging SET status = 'CLAIMED', claimed_by = :pod, "
			+ "claimed_at = now(), attempts = attempts + 1 "
			+ "WHERE id IN (SELECT id FROM usage_ledger_staging "
			+ "WHERE status = 'PENDING' AND next_retry_at <= now() "
			+ "ORDER BY next_retry_at, created_at FOR UPDATE SKIP LOCKED LIMIT :limit) "
			+ "RETURNING *", nativeQuery = true)
	List<LedgerStagingEntry> claimBatch(@Param("pod") String pod, @Param("limit") int limit);

	/**
	 * Returns crashed consumers' rows to the pool: anything claimed longer ago than {@code cutoff} without
	 * completing becomes claimable again (attempts already counted the lost try).
	 *
	 * @return reclaimed row count
	 */
	@Modifying
	@Query("UPDATE LedgerStagingEntry e SET e.status = 'PENDING', e.claimedBy = null "
			+ "WHERE e.status = 'CLAIMED' AND e.claimedAt < :cutoff")
	int resetStaleClaims(@Param("cutoff") Instant cutoff);

	/**
	 * Retention purge for terminal rows.
	 *
	 * @return deleted row count
	 */
	@Modifying
	@Query("DELETE FROM LedgerStagingEntry e WHERE e.status IN ('DONE', 'POISONED') AND e.createdAt < :cutoff")
	int purgeCompleted(@Param("cutoff") Instant cutoff);
}
