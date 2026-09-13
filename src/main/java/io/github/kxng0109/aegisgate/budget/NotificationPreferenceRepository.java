package io.github.kxng0109.aegisgate.budget;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link NotificationPreference}. Scope lookups fan out one alert to every subscribed channel.
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

	List<NotificationPreference> findByScope(String scope);
}
