package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the alert-event entity: field mapping, JPA-spec constructors, and state transitions.
 */
@DisplayName("AlertEvent")
class AlertEventTest {

	@Test
	@DisplayName("constructor maps every outbox field with pending defaults")
	void constructorMapsFields() {
		AlertEvent event = new AlertEvent("sha", "KEY:hex", "burn_static", "warning",
				Instant.parse("2026-09-12T14:33:00Z"), "{}", "50.00", "2026-09");

		assertThat(event.getId()).isNotNull();
		assertThat(event.getDedupeSha()).isEqualTo("sha");
		assertThat(event.getScope()).isEqualTo("KEY:hex");
		assertThat(event.getDetector()).isEqualTo("burn_static");
		assertThat(event.getSeverity()).isEqualTo("warning");
		assertThat(event.getStartsAt()).isEqualTo(Instant.parse("2026-09-12T14:33:00Z"));
		assertThat(event.getEndsAt()).isNull();
		assertThat(event.getPayload()).isEqualTo("{}");
		assertThat(event.getValueText()).isEqualTo("50.00");
		assertThat(event.getMonth()).isEqualTo("2026-09");
		assertThat(event.getStatus()).isEqualTo("PENDING");
		assertThat(event.getAttempts()).isZero();
		assertThat(event.getNextRetryAt()).isNotNull();
		assertThat(event.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("no-argument constructor exists for JPA")
	void noArgumentConstructorExists() {
		assertThat(new AlertEvent().getId()).isNull();
	}

	@Test
	@DisplayName("state transitions move forward only")
	void stateTransitionsMoveForward() {
		AlertEvent event = new AlertEvent("sha", "KEY:hex", "burn_static", "warning", Instant.now(), "{}",
				"50.00", "2026-09");

		event.markSent();
		assertThat(event.getStatus()).isEqualTo("SENT");

		event.backoff(Instant.now().plusSeconds(60));
		assertThat(event.getStatus()).isEqualTo("SENT");
		assertThat(event.getAttempts()).isEqualTo(1);

		event.markDead();
		assertThat(event.getStatus()).isEqualTo("FAILED");

		event.markResolved();
		assertThat(event.getStatus()).isEqualTo("RESOLVED");
	}
}
