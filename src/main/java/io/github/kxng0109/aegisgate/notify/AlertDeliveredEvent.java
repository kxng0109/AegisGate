package io.github.kxng0109.aegisgate.notify;

import java.time.Instant;

/**
 * A successfully Alertmanager-delivered alert, fanned out to opt-in channels after the outbox transaction
 * commits. Carries detached values only (never entities).
 */
public record AlertDeliveredEvent(
		String dedupeSha,
		String scope,
		String detector,
		String severity,
		Instant startsAt,
		String value,
		String month
) {
}
