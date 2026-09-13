package io.github.kxng0109.aegisgate.notify;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;

/**
 * One outbound notification channel (Teams, Slack, generic webhook, email). Implementations are synchronous,
 * short-timeout, and side-effect free beyond the single POST; retry and audit live in the fan-out.
 */
public interface ChannelSender {

	/**
	 * @return the preference channel name this sender handles ({@code email}, {@code teams}, ...)
	 */
	String channel();

	/**
	 * Sends one alert payload to one subscription target.
	 *
	 * @param preference the subscription (target URL, secret ref)
	 * @param payload    PII-free alert content
	 * @return delivery outcome
	 */
	ChannelResult send(NotificationPreference preference, NotificationPayload payload);
}
