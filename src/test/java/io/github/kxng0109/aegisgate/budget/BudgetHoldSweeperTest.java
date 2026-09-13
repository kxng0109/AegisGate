package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the hold sweeper with a stubbed datasource: lock contention skips the tick, a won lock expires
 * due holds, per-hold failures do not abort the sweep, and the kill-switch disables everything.
 */
@DisplayName("BudgetHoldSweeper")
class BudgetHoldSweeperTest {

	private static final BudgetSettlementProperties ENABLED =
			new BudgetSettlementProperties(true, 4096, 3600L, 30L, 500);

	private record Harness(DataSource dataSource, Connection connection, BudgetSettlement settlement,
	                       BudgetHoldSweeper sweeper) {
	}

	private static Harness harness(boolean lockWon, Set<String> due) throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		PreparedStatement unlockStatement = mock(PreparedStatement.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))"))
				.thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(lockWon);
		when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))"))
				.thenReturn(unlockStatement);
		BudgetSettlement settlement = mock(BudgetSettlement.class);
		when(settlement.dueHoldKeys(anyInt())).thenReturn(due);
		BudgetHoldSweeper sweeper = new BudgetHoldSweeper(dataSource, settlement, ENABLED);
		return new Harness(dataSource, connection, settlement, sweeper);
	}

	@Test
	@DisplayName("lost lock skips the tick without touching holds")
	void lostLockSkipsTick() throws Exception {
		Harness harness = harness(false, Set.of(BudgetEnforcer.holdKey("hold-1")));

		harness.sweeper().sweep();

		verify(harness.settlement(), never()).dueHoldKeys(anyInt());
		verify(harness.settlement(), never()).expireDueHold(anyString());
	}

	@Test
	@DisplayName("won lock expires every due hold then unlocks")
	void wonLockExpiresDueHolds() throws Exception {
		Harness harness = harness(true, Set.of(
				BudgetEnforcer.holdKey("hold-1"), BudgetEnforcer.holdKey("hold-2")));
		when(harness.settlement().expireDueHold(anyString())).thenReturn(true);

		harness.sweeper().sweep();

		verify(harness.settlement()).expireDueHold(BudgetEnforcer.holdKey("hold-1"));
		verify(harness.settlement()).expireDueHold(BudgetEnforcer.holdKey("hold-2"));
	}

	@Test
	@DisplayName("per-hold failure does not abort the sweep")
	void perHoldFailureContinuesSweep() throws Exception {
		Harness harness = harness(true, Set.of(
				BudgetEnforcer.holdKey("hold-1"), BudgetEnforcer.holdKey("hold-2")));
		when(harness.settlement().expireDueHold(BudgetEnforcer.holdKey("hold-1")))
				.thenThrow(new RuntimeException("boom"));
		when(harness.settlement().expireDueHold(BudgetEnforcer.holdKey("hold-2"))).thenReturn(true);

		harness.sweeper().sweep();

		verify(harness.settlement()).expireDueHold(BudgetEnforcer.holdKey("hold-2"));
	}

	@Test
	@DisplayName("disabled kill-switch skips everything including the datasource")
	void disabledSkipsEverything() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		BudgetHoldSweeper sweeper = new BudgetHoldSweeper(dataSource, mock(BudgetSettlement.class),
				new BudgetSettlementProperties(false, 4096, 3600L, 30L, 500));

		sweeper.sweep();

		verify(dataSource, never()).getConnection();
	}

	@Test
	@DisplayName("datasource outage skips the tick")
	void datasourceOutageSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
		BudgetSettlement settlement = mock(BudgetSettlement.class);
		BudgetHoldSweeper sweeper = new BudgetHoldSweeper(dataSource, settlement, ENABLED);

		sweeper.sweep();

		verify(settlement, never()).dueHoldKeys(anyInt());
	}

	@Test
	@DisplayName("unlock failure is absorbed after a completed sweep")
	void unlockFailureAbsorbed() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))"))
				.thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))"))
				.thenThrow(new SQLException("conn gone"));
		BudgetSettlement settlement = mock(BudgetSettlement.class);
		when(settlement.dueHoldKeys(anyInt())).thenReturn(Set.of());
		BudgetHoldSweeper sweeper = new BudgetHoldSweeper(dataSource, settlement, ENABLED);

		sweeper.sweep();

		verify(settlement).dueHoldKeys(anyInt());
	}

	@Test
	@DisplayName("sweep stops at the batch bound")
	void sweepStopsAtBatchBound() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		PreparedStatement unlockStatement = mock(PreparedStatement.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))"))
				.thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))"))
				.thenReturn(unlockStatement);
		BudgetSettlement settlement = mock(BudgetSettlement.class);
		when(settlement.dueHoldKeys(1)).thenReturn(new LinkedHashSet<>(List.of(
				BudgetEnforcer.holdKey("hold-1"), BudgetEnforcer.holdKey("hold-2"))));
		when(settlement.expireDueHold(anyString())).thenReturn(true);
		BudgetHoldSweeper sweeper = new BudgetHoldSweeper(dataSource, settlement,
				new BudgetSettlementProperties(true, 4096, 3600L, 30L, 1));

		sweeper.sweep();

		verify(settlement).expireDueHold(BudgetEnforcer.holdKey("hold-1"));
		verify(settlement, never()).expireDueHold(BudgetEnforcer.holdKey("hold-2"));
	}
}
