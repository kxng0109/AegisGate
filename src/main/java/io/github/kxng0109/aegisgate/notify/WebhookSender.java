package io.github.kxng0109.aegisgate.notify;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Generic HTTPS webhooks with Stripe-style HMAC-SHA256 signing: {@code X-Aegis-Timestamp} (unix seconds) and
 * {@code X-Aegis-Signature: t=&lt;ts&gt;,v1=&lt;hex(hmac(secret, ts + "." + body))&gt;}. Receivers reject
 * messages older than 5 minutes (replay protection) and compare signatures in constant time. A missing or
 * malformed secret reference is a terminal configuration error (never retried, never sent unsigned).
 */
@Component
public class WebhookSender extends BaseSender implements ChannelSender {

	@Autowired
	public WebhookSender(SsrfValidator ssrfValidator) {
		super(ssrfValidator);
	}

	WebhookSender(SsrfValidator ssrfValidator, HttpClient httpClient, ObjectMapper objectMapper) {
		super(ssrfValidator, httpClient, objectMapper);
	}

	@Override
	public String channel() {
		return "webhook";
	}

	@Override
	public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
		String secret = resolveSecret(preference.getSecretRef());
		if (secret == null) {
			return ChannelResult.TERMINAL;
		}
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body;
		try {
			body = objectMapper.writeValueAsString(Map.of(
					"scope", payload.scope(),
					"detector", payload.detector(),
					"severity", payload.severity(),
					"starts_at", payload.startsAt().toString(),
					"value", payload.value(),
					"month", payload.month()));
		} catch (Exception ex) {
			return ChannelResult.TERMINAL;
		}
		String signature;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
			signature = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
		} catch (Exception ex) {
			return ChannelResult.TERMINAL;
		}
		return postRaw(preference.getTarget(), body, Map.of(
				"X-Aegis-Timestamp", timestamp,
				"X-Aegis-Signature", signature));
	}
}
