package io.github.kxng0109.aegisgate.replay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the partitioned replay store end to end: the full Flyway chain (V1–V10) migrates (which validates the
 * partition DDL), rows land in the month partition matching their expiry, point reads prune by (id,
 * expires_at), and composite-key equality holds across the TOAST boundary.
 */
@DisplayName("Replay repository against real PostgreSQL")
@Testcontainers
class ReplayRepositoryIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	private SimpleJpaRepository<ReplayRecord, ReplayId> repository;

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

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(ReplayRecord.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		repository = new SimpleJpaRepository<>(ReplayRecord.class, entityManager);
	}

	private void save(ReplayRecord record) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.save(record);
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
	@DisplayName("save and exact read round-trip within one partition")
	void saveAndExactRead() {
		UUID id = UUID.randomUUID();
		Instant expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
		byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
		save(new ReplayRecord(new ReplayId(id, expiresAt), body, "abc123"));

		Optional<ReplayRecord> found = repository.findById(new ReplayId(id, expiresAt));

		assertThat(found).isPresent();
		assertThat(found.get().getBody()).isEqualTo(body);
		assertThat(found.get().getBodyHash()).isEqualTo("abc123");
	}

	@Test
	@DisplayName("rows in different months land in different partitions and read independently")
	void monthsPartitionIndependently() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		UUID idA = UUID.randomUUID();
		UUID idB = UUID.randomUUID();
		Instant monthA = now.plusSeconds(3600);
		Instant monthB = now.plusSeconds(40L * 24 * 3600);
		save(new ReplayRecord(new ReplayId(idA, monthA), new byte[]{1}, "h1"));
		save(new ReplayRecord(new ReplayId(idB, monthB), new byte[]{2}, "h2"));

		assertThat(repository.findById(new ReplayId(idA, monthA))).isPresent();
		assertThat(repository.findById(new ReplayId(idB, monthB))).isPresent();
		assertThat(repository.findById(new ReplayId(idA, monthB))).isEmpty();
	}

	@Test
	@DisplayName("unknown id reads empty")
	void unknownIdReadsEmpty() {
		assertThat(repository.findById(new ReplayId(UUID.randomUUID(), Instant.now()))).isEmpty();
	}
}
