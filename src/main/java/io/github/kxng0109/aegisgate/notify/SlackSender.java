package io.github.kxng0109.aegisgate.notify;

import java.net.http.HttpClient;
import java.util.Map;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack delivery via incoming webhooks: a single {@code text} field (Block Kit is intentionally not used —
 * plain text renders identically in every client and cannot smuggle formatting).
 */
@Component
public class SlackSender extends BaseSender implements ChannelSender {

	@Autowired
	public SlackSender(SsrfValidator ssrfValidator) {
		super(ssrfValidator);
	}

	SlackSender(SsrfValidator ssrfValidator, HttpClient httpClient, ObjectMapper objectMapper) {
		super(ssrfValidator, httpClient, objectMapper);
	}

	@Override
	public String channel() {
		return "slack";
	}

	@Override
	public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
		return postJson(preference.getTarget(), Map.of("text", payload.toText()), Map.of());
	}
}
