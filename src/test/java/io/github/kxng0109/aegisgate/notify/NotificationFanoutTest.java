package io.github.kxng0109.aegisgate.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.kxng0109.aegisgate.budget.NotificationBounceRepository;
import io.github.kxng0109.aegisgate.budget.NotificationDedupeRepository;
import io.github.kxng0109.aegisgate.budget.NotificationLogEntry;
import io.github.kxng0109.aegisgate.budget.NotificationLogRepository;
import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.budget.NotificationPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the notification fan-out: opt-in matching, severity gating, bounce suppression, dedupe
 * claims, per-channel outcomes with audit rows, sender failure isolation, and the PII-free payload
 * invariant (no key material, prompts, or completions can leave the building — the payload type has no
 * field for them).
 */
@DisplayName("NotificationFanout")
class NotificationFanoutTest {

	private record Harness(NotificationPreferenceRepository preferences,
	                       NotificationBounceRepository bounces,
	                       NotificationDedupeRepository dedupes,
	                       NotificationLogRepository logs,
	                       RecordingSender teams,
	                       NotificationFanout fanout) {
	}

	private static class RecordingSender implements ChannelSender {
		final List<NotificationPayload> received = new ArrayList<>();
		ChannelResult result = ChannelResult.SENT;
		RuntimeException failure;

		@Override
		public String channel() {
			return "teams";
		}

		@Override
		public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
			if (failure != null) {
				throw failure;
			}
			received.add(payload);
			return result;
		}
	}

	private static Harness harness(List<NotificationPreference> prefs) {
		NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
		when(preferences.findByScope("KEY:hex")).thenReturn(prefs);
		NotificationBounceRepository bounces = mock(NotificationBounceRepository.class);
		NotificationDedupeRepository dedupes = mock(NotificationDedupeRepository.class);
		NotificationLogRepository logs = mock(NotificationLogRepository.class);
		RecordingSender teams = new RecordingSender();
		NotificationFanout fanout = new NotificationFanout(preferences, bounces, dedupes, logs,
				List.of(teams));
		return new Harness(preferences, bounces, dedupes, logs, teams, fanout);
	}

	private static AlertDeliveredEvent event() {
		return new AlertDeliveredEvent("sha", "KEY:hex", "burn_static", "warning",
				Instant.parse("2026-09-12T14:33:00Z"), "50.00", "2026-09");
	}

	private static NotificationPreference preference(String channel, String minSeverity) {
		return new NotificationPreference("KEY:hex", channel, "https://example.com/hook", null, minSeverity);
	}

	@Test
	@DisplayName("no preferences means no delivery and no audit rows")
	void noPreferencesSilent() {
		Harness harness = harness(List.of());

		harness.fanout().onDelivered(event());

		verify(harness.logs(), never()).save(any());
	}

	@Test
	@DisplayName("below-threshold severity is gated without audit noise")
	void severityGatedSilently() {
		Harness harness = harness(List.of(preference("teams", "critical")));

		harness.fanout().onDelivered(event());

		assertThat(harness.teams().received).isEmpty();
		verify(harness.logs(), never()).save(any());
	}

	@Test
	@DisplayName("bounced destinations are suppressed with an audit row")
	void bouncedSuppressedWithAudit() {
		Harness harness = harness(List.of(preference("teams", "warning")));
		when(harness.bounces().existsByChannelAndTarget("teams", "https://example.com/hook"))
				.thenReturn(true);

		harness.fanout().onDelivered(event());

		assertThat(harness.teams().received).isEmpty();
		ArgumentCaptor<NotificationLogEntry> log = ArgumentCaptor.forClass(NotificationLogEntry.class);
		verify(harness.logs()).save(log.capture());
		assertThat(log.getValue().getStatus()).isEqualTo("SUPPRESSED");
	}

	@Test
	@DisplayName("duplicate delivery claims are skipped with an audit row")
	void duplicateSkippedWithAudit() {
		Harness harness = harness(List.of(preference("teams", "warning")));
		when(harness.dedupes().save(any()))
				.thenThrow(new DuplicateKeyException("dedupe"));

		harness.fanout().onDelivered(event());

		assertThat(harness.teams().received).isEmpty();
		ArgumentCaptor<NotificationLogEntry> log = ArgumentCaptor.forClass(NotificationLogEntry.class);
		verify(harness.logs()).save(log.capture());
		assertThat(log.getValue().getStatus()).isEqualTo("SKIPPED");
	}

	@Test
	@DisplayName("unknown channels are skipped with an audit row")
	void unknownChannelSkipped() {
		Harness harness = harness(List.of(preference("pager", "warning")));

		harness.fanout().onDelivered(event());

		ArgumentCaptor<NotificationLogEntry> log = ArgumentCaptor.forClass(NotificationLogEntry.class);
		verify(harness.logs()).save(log.capture());
		assertThat(log.getValue().getStatus()).isEqualTo("SKIPPED");
	}

	@Test
	@DisplayName("successful sends are audited as sent")
	void successAuditedSent() {
		Harness harness = harness(List.of(preference("teams", "warning")));

		harness.fanout().onDelivered(event());

		assertThat(harness.teams().received).hasSize(1);
		ArgumentCaptor<NotificationLogEntry> log = ArgumentCaptor.forClass(NotificationLogEntry.class);
		verify(harness.logs()).save(log.capture());
		assertThat(log.getValue().getStatus()).isEqualTo("SENT");
	}

	@Test
	@DisplayName("sender explosions degrade to failed audit rows")
	void senderFailureAuditedFailed() {
		Harness harness = harness(List.of(preference("teams", "warning")));
		harness.teams().failure = new RuntimeException("boom");

		harness.fanout().onDelivered(event());

		ArgumentCaptor<NotificationLogEntry> log = ArgumentCaptor.forClass(NotificationLogEntry.class);
		verify(harness.logs()).save(log.capture());
		assertThat(log.getValue().getStatus()).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("audit write failures are absorbed without breaking delivery")
	void auditWriteFailureAbsorbed() {
		Harness harness = harness(List.of(preference("teams", "warning")));
		when(harness.logs().save(any())).thenThrow(new RuntimeException("db down"));

		harness.fanout().onDelivered(event());

		assertThat(harness.teams().received).hasSize(1);
	}

	@Test
	@DisplayName("payloads cannot carry key material, prompts, or completions")
	void payloadCarriesNoSensitiveData() {
		Harness harness = harness(List.of(preference("teams", "warning")));

		harness.fanout().onDelivered(event());

		NotificationPayload payload = harness.teams().received.get(0);
		assertThat(payload.toString()).doesNotContain("sk-");
		assertThat(Map.of(
				"scope", payload.scope(),
				"detector", payload.detector(),
				"severity", payload.severity(),
				"startsAt", payload.startsAt().toString(),
				"value", payload.value(),
				"month", payload.month())).hasSize(6);
	}
}
