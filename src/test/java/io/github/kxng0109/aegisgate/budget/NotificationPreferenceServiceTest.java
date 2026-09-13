package io.github.kxng0109.aegisgate.budget;

import java.util.List;
import java.util.UUID;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for subscription validation: channel shapes, target shapes, SSRF refusal, secret-reference
 * shape, severity defaulting, duplicate collapse, and listing/deletion.
 */
@DisplayName("NotificationPreferenceService")
class NotificationPreferenceServiceTest {

	private record Harness(NotificationPreferenceRepository repository, NotificationPreferenceService service) {
	}

	private static Harness harness() {
		NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		return new Harness(repository,
				new NotificationPreferenceService(repository, new SsrfValidator()));
	}

	@Test
	@DisplayName("valid email subscription is accepted with defaulted severity")
	void validEmailAccepted() {
		Harness harness = harness();

		NotificationPreference created = harness.service()
				.create("KEY:hex", "email", "oncall@example.com", null, null);

		assertThat(created.getChannel()).isEqualTo("email");
		assertThat(created.getMinSeverity()).isEqualTo("warning");
		assertThat(created.getSecretRef()).isNull();
	}

	@Test
	@DisplayName("malformed email is rejected")
	void malformedEmailRejected() {
		Harness harness = harness();

		assertThatThrownBy(() -> harness.service().create("KEY:hex", "email", "not-an-email", null, null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
		assertThatThrownBy(() -> harness.service().create("KEY:hex", "email", null, null, null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
	}

	@Test
	@DisplayName("malformed webhook targets are rejected")
	void malformedTargetRejected() {
		Harness harness = harness();

		assertThatThrownBy(() -> harness.service().create("KEY:hex", "webhook", "http://[::1", null, null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
	}

	@Test
	@DisplayName("blank secret references and severities default cleanly")
	void blankRefAndSeverityDefault() {
		Harness harness = harness();

		NotificationPreference created = harness.service()
				.create("KEY:hex", "email", "oncall@example.com", "  ", "  ");

		assertThat(created.getSecretRef()).isNull();
		assertThat(created.getMinSeverity()).isEqualTo("warning");
	}

	@Test
	@DisplayName("SSRF-blocked webhook targets are rejected without persistence")
	void blockedTargetRejected() {
		Harness harness = harness();

		assertThatThrownBy(() ->
				harness.service().create("KEY:hex", "webhook", "http://169.254.169.254/hook", null, null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
		verify(harness.repository(), org.mockito.Mockito.never()).save(any());
	}

	@Test
	@DisplayName("malformed secret references are rejected")
	void malformedSecretRefRejected() {
		Harness harness = harness();

		assertThatThrownBy(() -> harness.service()
				.create("KEY:hex", "webhook", "https://example.com/hook", "bad ref!", null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
	}

	@Test
	@DisplayName("duplicate subscriptions collapse to conflict")
	void duplicateCollapsesToConflict() {
		NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
		when(repository.save(any())).thenThrow(new DuplicateKeyException("prefs"));
		NotificationPreferenceService service =
				new NotificationPreferenceService(repository, new SsrfValidator());

		assertThatThrownBy(() -> service.create("KEY:hex", "teams", "https://example.com/hook", null, null))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("409");
	}

	@Test
	@DisplayName("listing and deletion delegate to the repository")
	void listAndDeleteDelegate() {
		Harness harness = harness();
		UUID id = UUID.randomUUID();
		when(harness.repository().findByScope("KEY:hex")).thenReturn(List.of());

		assertThat(harness.service().list("KEY:hex")).isEmpty();
		harness.service().delete(id);
		verify(harness.repository()).deleteById(id);
	}
}
