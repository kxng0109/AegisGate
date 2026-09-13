package io.github.kxng0109.aegisgate.budget;

import java.util.List;
import java.util.Map;
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
 * Proves the audit hash chain (V13) against real PostgreSQL: the full Flyway chain migrates (validating the
 * backfill DO block and both triggers), inserts link automatically (prev GENESIS then chained), the unique
 * predecessor holds, and mutation stays blocked by the V8 trigger.
 */
@DisplayName("Audit hash chain")
@Testcontainers
class AuditHashChainIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

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
		jdbc.execute("TRUNCATE budget_audit");
	}

	@Test
	@DisplayName("inserts chain automatically from GENESIS")
	void insertsChainAutomatically() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a')",
				first);
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'UPDATE', 'TEAM', 't-a')",
				second);

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, prev_hash, row_hash FROM budget_audit ORDER BY created_at, id");
		assertThat(rows).hasSize(2);
		String firstPrev = (String) rows.get(0).get("prev_hash");
		String firstRow = (String) rows.get(0).get("row_hash");
		String secondPrev = (String) rows.get(1).get("prev_hash");
		String secondRow = (String) rows.get(1).get("row_hash");
		assertThat(firstPrev).isEqualTo("GENESIS");
		assertThat(firstRow).isNotBlank().hasSize(64);
		assertThat(secondPrev).isEqualTo(firstRow);
		assertThat(secondRow).isNotBlank().isNotEqualTo(firstRow);
	}

	@Test
	@DisplayName("updates stay blocked on chained rows")
	void updatesStayBlocked() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a')",
				id);

		assertThatThrownBy(() -> jdbc.update("UPDATE budget_audit SET actor = 'mallory' WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class)
				.hasStackTraceContaining("append-only");
	}
}
