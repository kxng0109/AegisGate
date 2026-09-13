package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the advisory-lock helper with a stubbed JDBC connection: acquire, contention, and
 * best-effort release.
 */
@DisplayName("AdvisoryLock")
class AdvisoryLockTest {

	@Test
	@DisplayName("won lock returns true and unlock executes")
	void wonLockUnlocks() throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		PreparedStatement unlockStatement = mock(PreparedStatement.class);
		when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))"))
				.thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))"))
				.thenReturn(unlockStatement);

		assertThat(AdvisoryLock.tryLock(connection, "test-lock")).isTrue();
		AdvisoryLock.unlock(connection, "test-lock");
		verify(unlockStatement).execute();
	}

	@Test
	@DisplayName("contended lock returns false")
	void contendedLockReturnsFalse() throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(false);

		assertThat(AdvisoryLock.tryLock(connection, "test-lock")).isFalse();
	}

	@Test
	@DisplayName("empty lock result returns false")
	void emptyLockResultReturnsFalse() throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(false);

		assertThat(AdvisoryLock.tryLock(connection, "test-lock")).isFalse();
	}

	@Test
	@DisplayName("unlock failure is absorbed")
	void unlockFailureAbsorbed() throws Exception {
		Connection connection = mock(Connection.class);
		when(connection.prepareStatement(anyString())).thenThrow(new SQLException("conn gone"));

		AdvisoryLock.unlock(connection, "test-lock");
	}
}
