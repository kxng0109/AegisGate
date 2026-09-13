package io.github.kxng0109.aegisgate.admin;

import java.util.List;
import java.util.UUID;

import io.github.kxng0109.aegisgate.admin.dto.CreateNotificationRequest;
import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.budget.NotificationPreferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for notification administration endpoints with a mocked service.
 */
@DisplayName("AdminNotificationController")
class AdminNotificationControllerTest {

	private final NotificationPreferenceService preferenceService = mock(NotificationPreferenceService.class);

	private final AdminNotificationController controller = new AdminNotificationController(preferenceService);

	@Test
	@DisplayName("create returns 201 with the created subscription")
	void createReturns201() {
		NotificationPreference preference =
				new NotificationPreference("KEY:hex", "teams", "https://example.com/hook", null, "warning");
		when(preferenceService.create("KEY:hex", "teams", "https://example.com/hook", null, null))
				.thenReturn(preference);

		var response = controller.createSubscription(
				new CreateNotificationRequest("KEY:hex", "teams", "https://example.com/hook", null, null));

		assertEquals(HttpStatus.CREATED, response.getStatusCode());
		assertEquals("teams", response.getBody().channel());
		verify(preferenceService).create("KEY:hex", "teams", "https://example.com/hook", null, null);
	}

	@Test
	@DisplayName("list returns 200 with the scope subscriptions")
	void listReturns200() {
		NotificationPreference preference =
				new NotificationPreference("KEY:hex", "email", "oncall@example.com", null, "critical");
		when(preferenceService.list("KEY:hex")).thenReturn(List.of(preference));

		var response = controller.listSubscriptions("KEY:hex");

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(1, response.getBody().size());
		assertEquals("critical", response.getBody().get(0).minSeverity());
	}

	@Test
	@DisplayName("delete returns 204")
	void deleteReturns204() {
		UUID id = UUID.randomUUID();

		var response = controller.deleteSubscription(id);

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
		verify(preferenceService).delete(id);
	}
}
