package io.github.kxng0109.aegisgate.notify;

import java.net.http.HttpClient;
import java.util.Map;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Microsoft Teams delivery via Power Automate workflow webhooks ("When a Teams webhook request is received").
 * Posts a minimal {@code text} card: the flow author maps {@code triggerBody()?['text']} to the channel
 * message. Legacy Office 365 connectors are retired and are not supported.
 */
@Component
public class TeamsSender extends BaseSender implements ChannelSender {

	@Autowired
	public TeamsSender(SsrfValidator ssrfValidator) {
		super(ssrfValidator);
	}

	TeamsSender(SsrfValidator ssrfValidator, HttpClient httpClient, ObjectMapper objectMapper) {
		super(ssrfValidator, httpClient, objectMapper);
	}

	@Override
	public String channel() {
		return "teams";
	}

	@Override
	public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
		return postJson(preference.getTarget(), Map.of("text", payload.toText()), Map.of());
	}
}
