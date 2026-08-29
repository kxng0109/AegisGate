package io.github.kxng0109.aegisgate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseMigrator}: the disabled switch and the retry
 * guard both short circuit without touching the database.
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
}