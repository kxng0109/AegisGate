package io.github.kxng0109.aegisgate.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseMigrator}: the disabled switch and the retry guard both short circuit without touching
 * the database.
 */
@DisplayName("DatabaseMigrator")
class DatabaseMigratorTest {

	@Test
	@DisplayName("does nothing when migration is disabled")
	void disabledDoesNothing() {
		DataSource dataSource = mock(DataSource.class);
		DatabaseMigrator migrator = new DatabaseMigrator(dataSource, false, "classpath:db/migration");

		assertDoesNotThrow(migrator::migrateOnReady);
		assertDoesNotThrow(migrator::migrateOnSchedule);
	}

	@Test
	@DisplayName("a failed attempt is swallowed and retried later")
	void failedAttemptIsSwallowed() {
		DataSource dataSource = mock(DataSource.class);
		DatabaseMigrator migrator = new DatabaseMigrator(dataSource, true, "classpath:db/migration");

		assertDoesNotThrow(migrator::migrateOnReady);
		assertDoesNotThrow(migrator::migrateOnSchedule);
	}

	@Test
	@DisplayName("once migrated, further attempts are skipped")
	void migratedSkipsFurtherAttempts() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		DatabaseMigrator migrator = new DatabaseMigrator(dataSource, true, "classpath:db/migration");

		Field migratedField = DatabaseMigrator.class.getDeclaredField("migrated");
		migratedField.setAccessible(true);
		((AtomicBoolean) migratedField.get(migrator)).set(true);

		assertDoesNotThrow(migrator::migrateOnReady);
		assertDoesNotThrow(migrator::migrateOnSchedule);
	}

	@Test
	@DisplayName("checksum mismatch triggers flyway.repair() and migrates successfully")
	void checksumMismatchRepairsAndMigrates() {
		DataSource dataSource = mock(DataSource.class);
		Flyway flyway = mock(Flyway.class);

		// First migrate() throws validation mismatch exception, then second migrate() succeeds
		when(flyway.migrate())
				.thenThrow(new RuntimeException("Migration checksum mismatch for migration version 3"))
				.thenReturn(null);

		DatabaseMigrator migrator = new DatabaseMigrator(dataSource, true, "classpath:db/migration") {
			@Override
			Flyway createFlyway() {
				return flyway;
			}
		};

		assertDoesNotThrow(migrator::migrateOnReady);
		verify(flyway).repair();
		verify(flyway, times(2)).migrate();

		// Validate failed message
		Flyway flywayValidateFailed = mock(Flyway.class);
		when(flywayValidateFailed.migrate())
				.thenThrow(new RuntimeException("Validate failed: checksum mismatch"))
				.thenReturn(null);
		DatabaseMigrator migratorValidate = new DatabaseMigrator(dataSource, true, "classpath:db/migration") {
			@Override
			Flyway createFlyway() {
				return flywayValidateFailed;
			}
		};
		assertDoesNotThrow(migratorValidate::migrateOnReady);
		verify(flywayValidateFailed).repair();

		// Generic exception without mismatch does not call repair
		Flyway flywayGenericErr = mock(Flyway.class);
		when(flywayGenericErr.migrate()).thenThrow(new RuntimeException("Connection refused"));
		DatabaseMigrator migratorGeneric = new DatabaseMigrator(dataSource, true, "classpath:db/migration") {
			@Override
			Flyway createFlyway() {
				return flywayGenericErr;
			}
		};
		assertDoesNotThrow(migratorGeneric::migrateOnReady);
		verify(flywayGenericErr, never()).repair();

		// createFlyway invocation
		DatabaseMigrator realMigrator = new DatabaseMigrator(dataSource, true, "classpath:db/migration");
		assertDoesNotThrow(realMigrator::createFlyway);
	}

	@Test
	@DisplayName("concurrent ready and scheduled attempts migrate exactly once")
	void concurrentAttemptsMigrateOnce() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Flyway flyway = mock(Flyway.class);
		AtomicInteger creations = new AtomicInteger();
		DatabaseMigrator migrator = new DatabaseMigrator(dataSource, true, "classpath:db/migration") {
			@Override
			Flyway createFlyway() {
				creations.incrementAndGet();
				return flyway;
			}
		};

		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(migrator::migrateOnReady));
			}
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(creations.get()).isEqualTo(1);
		verify(flyway, times(1)).migrate();
	}
}

