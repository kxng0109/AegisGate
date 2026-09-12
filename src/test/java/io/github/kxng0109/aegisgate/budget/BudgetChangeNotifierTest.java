package io.github.kxng0109.aegisgate.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for best-effort config fan-out: delivery failures must never break CRUD.
 */
@DisplayName("BudgetChangeNotifier")
class BudgetChangeNotifierTest {

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final BudgetChangeNotifier notifier = new BudgetChangeNotifier(jdbc);

	@Test
	@DisplayName("notifies the budget channel with level and subject")
	void notifiesChannel() {
		notifier.notifyChanged("TEAM", "tenant-a");

		verify(jdbc).execute("SELECT pg_notify('budget_config_changed', 'TEAM:tenant-a')");
	}

	@Test
	@DisplayName("delivery failure is swallowed")
	void failureSwallowed() {
		doThrow(new RuntimeException("pg down")).when(jdbc).execute(anyString());

		assertDoesNotThrow(() -> notifier.notifyChanged("ORG", "global"));
	}
}
