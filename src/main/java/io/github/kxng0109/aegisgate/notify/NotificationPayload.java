package io.github.kxng0109.aegisgate.notify;

import java.time.Instant;

/**
 * The only data that ever leaves the building for an alert: scope, detector identity, severity, timestamps,
 * and the numeric value. Prompts, completions, key material, and request bodies can never appear here by
 * construction (there is simply no field for them).
 */
public record NotificationPayload(
		String scope,
		String detector,
		String severity,
		Instant startsAt,
		String value,
		String month
) {

	/**
	 * Human-readable one-block rendering shared by text channels (Teams, Slack, email body).
	 */
	public String toText() {
		return "AegisGate budget alert [" + severity + "] " + detector + " on " + scope
				+ " value=" + value + " month=" + month + " at " + startsAt;
	}
}
