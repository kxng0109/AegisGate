package io.github.kxng0109.aegisgate.replay;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ReplayRecord}. Point reads always carry the full composite id (replay id plus expiry
 * instant, known from the hot-tier hash), so every query prunes to exactly one monthly partition.
 */
public interface ReplayRepository extends JpaRepository<ReplayRecord, ReplayId> {
}
