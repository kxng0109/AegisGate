package io.github.kxng0109.aegisgate.admin.dto;

import java.time.Instant;
import java.util.UUID;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;

/**
 * Delivery subscription view. The secret reference name is returned (it is public configuration); the secret
 * value itself never leaves the runtime environment.
 */
public record NotificationResponse(
		UUID id,
		String scope,
		String channel,
		String target,
		String secretRef,
		String minSeverity,
		Instant createdAt
) {

	public static NotificationResponse from(NotificationPreference preference) {
		return new NotificationResponse(
				preference.getId(),
				preference.getScope(),
				preference.getChannel(),
				preference.getTarget(),
				preference.getSecretRef(),
				preference.getMinSeverity(),
				preference.getCreatedAt());
	}
}
