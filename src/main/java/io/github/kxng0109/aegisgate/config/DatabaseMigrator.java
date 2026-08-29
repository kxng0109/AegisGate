package io.github.kxng0109.aegisgate.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies Flyway migrations without coupling the gateway's startup to the availability of PostgreSQL.
 *
 * <p>Spring Boot's own Flyway auto configuration fails startup when the
 * database is unreachable, which would take the whole gateway down because the ledger database is unavailable. The
 * gateway's contract is the reverse: the proxy, authentication, and rate limiting never touch the database, so a
 * database outage must not stop them. This component disables the automatic migration (see
 * {@code spring.flyway.enabled=false}) and runs the same migrations itself, once when the application is ready and then
 * on a retry schedule until they succeed, mirroring the bootstrap key seeding pattern. While the database is down,
 * ledger writes degrade to warnings and the dead letter file.</p>
 */
@Slf4j
@Component
public class DatabaseMigrator {

	private final DataSource dataSource;
	private final String locations;
	private final boolean enabled;
	private final AtomicBoolean migrated = new AtomicBoolean();

	/**
	 * @param dataSource the application data source
	 * @param enabled    whether migration is attempted at all
	 * @param locations  Flyway migration locations, classpath by default
	 */
	public DatabaseMigrator(
			DataSource dataSource,
			@Value("${gateway.database-migrate-enabled:true}") boolean enabled,
			@Value("${spring.flyway.locations:classpath:db/migration}") String locations
	) {
		this.dataSource = dataSource;
		this.enabled = enabled;
		this.locations = locations;
	}

	/**
	 * Attempts the migration once after the context is ready.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void migrateOnReady() {
		migrate();
	}

	/**
	 * Retries the migration on a schedule until it succeeds.
	 */
	@Scheduled(fixedDelayString = "${gateway.database-migrate-interval:30s}")
	public void migrateOnSchedule() {
		migrate();
	}

	private void migrate() {
		if (!enabled || migrated.get()) {
			return;
		}
		try {
			Flyway flyway = Flyway.configure()
			                      .dataSource(dataSource)
			                      .locations(locations)
			                      .load();
			flyway.migrate();
			migrated.set(true);
			log.info("Database migrations applied from {}", locations);
		} catch (Exception ex) {
			log.warn("Database migration could not run yet, will retry: {}", ex.getMessage());
		}
	}
}