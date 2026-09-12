package io.github.kxng0109.aegisgate.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for fan-out dispatch: key payloads invalidate exactly, everything else
 * invalidates all (over-invalidation costs one authoritative call; under-invalidation
 * admits spend).
 */
@DisplayName("BudgetConfigInvalidationListener dispatch")
class BudgetConfigInvalidationListenerTest {

	private final BudgetEnforcer enforcer = mock(BudgetEnforcer.class);

	private static PGNotification payload(String parameter) {
		PGNotification notification = mock(PGNotification.class);
		when(notification.getParameter()).thenReturn(parameter);
		return notification;
	}

	@Test
	@DisplayName("key payload invalidates that key only")
	void keyPayloadInvalidatesKey() {
		BudgetConfigInvalidationListener.dispatch(enforcer, new PGNotification[]{payload("KEY:ab12")});

		verify(enforcer).invalidate("ab12");
		verify(enforcer, never()).invalidateAll();
	}

	@Test
	@DisplayName("team and org payloads invalidate all")
	void scopePayloadsInvalidateAll() {
		BudgetConfigInvalidationListener.dispatch(
				enforcer, new PGNotification[]{payload("TEAM:tenant-a"), payload("ORG:global")});

		verify(enforcer, never()).invalidate("tenant-a");
		verify(enforcer, never()).invalidate("global");
		verify(enforcer, times(2)).invalidateAll();
	}

	@Test
	@DisplayName("malformed and null payloads invalidate all")
	void malformedInvalidatesAll() {
		BudgetConfigInvalidationListener.dispatch(
				enforcer, new PGNotification[]{payload("GARBAGE"), payload(""), null});
		BudgetConfigInvalidationListener.dispatch(enforcer, null);

		verify(enforcer, never()).invalidate("GARBAGE");
		verify(enforcer, times(3)).invalidateAll();
	}

	@Test
	@DisplayName("start and stop bracket a reconnecting loop without dispatching")
	void startStopLifecycle() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("pg down"));
		BudgetConfigInvalidationListener listener =
				new BudgetConfigInvalidationListener(dataSource, enforcer);

		listener.start();
		Thread.sleep(200);
		listener.stop();
		new BudgetConfigInvalidationListener(dataSource, enforcer).stop();

		verify(enforcer, never()).invalidateAll();
	}

	@Test
	@DisplayName("running listener exits cleanly on stop without dispatching")
	void runningListenerExitsOnStop() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PGConnection pg = mock(PGConnection.class);
		Statement statement = mock(Statement.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.unwrap(PGConnection.class)).thenReturn(pg);
		when(connection.createStatement()).thenReturn(statement);
		when(pg.getNotifications(10_000)).thenReturn(new PGNotification[0]);
		BudgetConfigInvalidationListener listener =
				new BudgetConfigInvalidationListener(dataSource, enforcer);

		listener.start();
		listener.start();
		Thread.sleep(200);
		listener.stop();

		verify(enforcer, never()).invalidateAll();
		verify(enforcer, never()).invalidate("x");
	}
}
