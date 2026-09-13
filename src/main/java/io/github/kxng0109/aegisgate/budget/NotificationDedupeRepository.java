package io.github.kxng0109.aegisgate.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link NotificationDedupe}.
 */
public interface NotificationDedupeRepository extends JpaRepository<NotificationDedupe, UUID> {

	boolean existsByDedupeShaAndChannelAndTarget(String dedupeSha, String channel, String target);
}
