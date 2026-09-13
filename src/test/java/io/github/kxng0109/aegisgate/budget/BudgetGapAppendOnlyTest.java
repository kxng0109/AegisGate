package io.github.kxng0109.aegisgate.budget;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@code budget_gap} is append-only at the database level (V9 trigger, same discipline as V8): UPDATE and
 * DELETE abort for every role including the table owner. The full Flyway chain (V1–V9) migrates first, which also
 * proves the V9 table and trigger definitions are valid SQL.
 */
@DisplayName("Budget gap append-only trigger")
@Testcontainers
class BudgetGapAppendOnlyTest {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	private JdbcTemplate jdbc;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[]{POSTGRES.getHost()});
		dataSource.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
		dataSource.setDatabaseName(POSTGRES.getDatabaseName());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).load().migrate();
		jdbc = new JdbcTemplate(dataSource);
	}

	@Test
	@DisplayName("gap rows reject UPDATE and DELETE")
	void gapRejectsMutation() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_gap (id, hold_id, level, subject_id, held_micros, settled_micros,"
						+ " orig_month, settle_month, reason)"
						+ " VALUES (?, 'hold-1', 'KEY', 'ab12', 10000, 4000, '2026-09', '2026-09', 'EXPIRED')",
				id);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM budget_gap WHERE id = ?", Integer.class, id)).isEqualTo(1);

		assertThatThrownBy(() ->
				jdbc.update("UPDATE budget_gap SET held_micros = 0 WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class)
				.hasStackTraceContaining("append-only");

		assertThatThrownBy(() ->
				jdbc.update("DELETE FROM budget_gap WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class)
				.hasStackTraceContaining("append-only");

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM budget_gap WHERE id = ?", Integer.class, id)).isEqualTo(1);
	}
}
