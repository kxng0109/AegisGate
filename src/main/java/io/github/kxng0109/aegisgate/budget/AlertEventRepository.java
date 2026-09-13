package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link AlertEvent}. Claims use {@code SKIP LOCKED} so concurrent dispatchers partition work
 * instead of contending.
 */
public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

	Optional<AlertEvent> findByDedupeSha(String dedupeSha);

	@Query(value = "SELECT * FROM alert_events WHERE status = 'PENDING' AND next_retry_at <= :now"
			+ " ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
	List<AlertEvent> claimDue(Instant now, int limit);
}
