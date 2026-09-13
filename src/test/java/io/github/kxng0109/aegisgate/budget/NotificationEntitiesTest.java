package io.github.kxng0109.aegisgate.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the notification entities: field mapping and JPA-spec constructors.
 */
@DisplayName("Notification entities")
class NotificationEntitiesTest {

	@Test
	@DisplayName("preference maps every subscription field")
	void preferenceMapsFields() {
		NotificationPreference preference =
				new NotificationPreference("KEY:hex", "webhook", "https://example.com/hook", "REF", "critical");

		assertThat(preference.getId()).isNotNull();
		assertThat(preference.getScope()).isEqualTo("KEY:hex");
		assertThat(preference.getChannel()).isEqualTo("webhook");
		assertThat(preference.getTarget()).isEqualTo("https://example.com/hook");
		assertThat(preference.getSecretRef()).isEqualTo("REF");
		assertThat(preference.getMinSeverity()).isEqualTo("critical");
		assertThat(preference.getCreatedAt()).isNotNull();
		assertThat(new NotificationPreference().getId()).isNull();
	}

	@Test
	@DisplayName("bounce maps every field")
	void bounceMapsFields() {
		NotificationBounce bounce = new NotificationBounce("slack", "https://example.com/x", "404");

		assertThat(bounce.getId()).isNotNull();
		assertThat(bounce.getChannel()).isEqualTo("slack");
		assertThat(bounce.getReason()).isEqualTo("404");
		assertThat(new NotificationBounce().getId()).isNull();
	}

	@Test
	@DisplayName("dedupe maps every field")
	void dedupeMapsFields() {
		NotificationDedupe dedupe = new NotificationDedupe("sha", "email", "a@example.com");

		assertThat(dedupe.getId()).isNotNull();
		assertThat(dedupe.getDedupeSha()).isEqualTo("sha");
		assertThat(new NotificationDedupe().getId()).isNull();
	}

	@Test
	@DisplayName("log entry maps every field")
	void logMapsFields() {
		NotificationLogEntry entry =
				new NotificationLogEntry("sha", "KEY:hex", "teams", "https://example.com/t", "SENT", "");

		assertThat(entry.getId()).isNotNull();
		assertThat(entry.getStatus()).isEqualTo("SENT");
		assertThat(entry.getDetail()).isEmpty();
		assertThat(entry.getCreatedAt()).isNotNull();
		assertThat(new NotificationLogEntry().getId()).isNull();
	}
}
