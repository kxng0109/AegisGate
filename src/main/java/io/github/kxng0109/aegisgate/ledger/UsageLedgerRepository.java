package io.github.kxng0109.aegisgate.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists {@link UsageLedgerEntry} rows.
 */
public interface UsageLedgerRepository extends JpaRepository<UsageLedgerEntry, UUID> {

	/**
	 * @param requestId the correlation id of the proxied request
	 * @return the entry recorded for that request, if any
	 */
	Optional<UsageLedgerEntry> findByRequestId(UUID requestId);

	/**
	 * @param requestId the correlation id of the proxied request
	 * @return {@code true} when an entry was already recorded for it
	 */
	boolean existsByRequestId(UUID requestId);
}