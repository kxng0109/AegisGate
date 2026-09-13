package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the alert outbox against real PostgreSQL: the full Flyway chain (V1–V11) migrates (validating the
 * outbox DDL), due-claims return pending rows in order, and the dedupe unique constraint collapses
 * double-evaluation at the database level.
 */
@DisplayName("Alert outbox against real PostgreSQL")
@Testcontainers
class AlertOutboxIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private SimpleJpaRepository<AlertEvent, UUID> repository;

	private EntityManager entityManager;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[]{POSTGRES.getHost()});
		dataSource.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
		dataSource.setDatabaseName(POSTGRES.getDatabaseName());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).load().migrate();
		new JdbcTemplate(dataSource).execute("TRUNCATE alert_events");

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(AlertEvent.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		repository = new SimpleJpaRepository<>(AlertEvent.class, entityManager);
	}

	private void save(AlertEvent event) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.save(event);
			transaction.commit();
		} catch (RuntimeException ex) {
			if (transaction.isActive()) {
				transaction.rollback();
			}
			throw ex;
		} finally {
			entityManager.clear();
		}
	}

	@Test
	@DisplayName("due claims return pending rows ordered by creation")
	void dueClaimsOrdered() {
		save(new AlertEvent("sha-1", "KEY:a", "burn_static", "warning", Instant.now(), "{}", "1.00", "2026-09"));
		save(new AlertEvent("sha-2", "KEY:b", "burn_static", "warning", Instant.now(), "{}", "1.00", "2026-09"));

		List<AlertEvent> claimed = entityManager.createNativeQuery(
				"SELECT * FROM alert_events WHERE status = 'PENDING' AND next_retry_at <= now()"
						+ " ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED",
				AlertEvent.class).getResultList();

		assertThat(claimed).hasSize(2);
		assertThat(claimed.get(0).getDedupeSha()).isEqualTo("sha-1");
	}

	@Test
	@DisplayName("duplicate dedupe aborts at the database level")
	void duplicateDedupeAborts() {
		save(new AlertEvent("sha-dup", "KEY:a", "burn_static", "warning", Instant.now(), "{}", "1.00", "2026-09"));

		assertThatThrownBy(() ->
				save(new AlertEvent("sha-dup", "KEY:a", "burn_static", "warning", Instant.now(), "{}", "1.00", "2026-09")))
				.hasStackTraceContaining("duplicate")
				.isInstanceOfAny(RuntimeException.class, DataIntegrityViolationException.class);
	}
}
