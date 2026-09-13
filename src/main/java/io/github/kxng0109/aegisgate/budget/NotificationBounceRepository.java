package io.github.kxng0109.aegisgate.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link NotificationBounce}.
 */
public interface NotificationBounceRepository extends JpaRepository<NotificationBounce, UUID> {

	boolean existsByChannelAndTarget(String channel, String target);
}
