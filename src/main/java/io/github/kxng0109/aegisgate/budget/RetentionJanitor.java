package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly retention maintenance: detaches expired replay partitions into standalone archive tables (data
 * preserved), rolls old sent alerts into the 1-year archive, purges what aged out, and trims send logs and
 * dedupe claims. Single-flight across pods via advisory lock; every step is independent and best-effort (a
 * failed step logs and continues, retried next month). Audit and gap tables are never touched.
 */
@Component
public class RetentionJanitor {

	static final String LOCK_NAME = "aegis-retention-janitor";

	static final int REPLAY_ARCHIVE_DAYS = 90;

	static final int ALERT_ARCHIVE_DAYS = 90;

	static final int ARCHIVE_PURGE_DAYS = 365;

	static final int LOG_PURGE_DAYS = 90;

	static final int DEDUPE_PURGE_DAYS = 30;

	private static final Logger log = LoggerFactory.getLogger(RetentionJanitor.class);

	private final JdbcTemplate jdbc;

	private final DataSource dataSource;

	private final MaintenanceProperties properties;

	public RetentionJanitor(JdbcTemplate jdbc, DataSource dataSource, MaintenanceProperties properties) {
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.properties = properties;
	}

	@Scheduled(cron = "${gateway.maintenance.retention-cron:0 0 3 1 * *}")
	public void retain() {
		if (!properties.retentionEnabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			if (!AdvisoryLock.tryLock(connection, LOCK_NAME)) {
				return;
			}
			try {
				detachExpiredReplayPartitions();
				archiveOldAlerts();
				purgeOldArchive();
				purgeOldLogs();
				purgeOldDedupes();
			} finally {
				AdvisoryLock.unlock(connection, LOCK_NAME);
			}
		} catch (SQLException ex) {
			log.warn("Retention tick skipped (datasource unavailable)");
		}
	}

	void detachExpiredReplayPartitions() {
		try {
			List<String> partitions = jdbc.queryForList(
					"SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = 'replay_store'::regclass",
					String.class);
			for (String partition : partitions) {
				try {
					String suffix = partition.substring("replay_store_".length());
					YearMonth end = YearMonth.parse(
							suffix.substring(0, 4) + "-" + suffix.substring(5, 7)).plusMonths(1);
					if (end.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
							.isBefore(Instant.now().minus(Duration.ofDays(REPLAY_ARCHIVE_DAYS)))) {
						jdbc.execute("ALTER TABLE replay_store DETACH PARTITION " + partition);
						log.info("Detached replay partition {} into standalone archive", partition);
					}
				} catch (RuntimeException ex) {
					log.warn("Skipping unparseable replay partition {}", partition);
				}
			}
		} catch (RuntimeException ex) {
			log.warn("Replay partition detach failed; next month retries");
		}
	}

	void archiveOldAlerts() {
		try {
			int moved = jdbc.update(
					"INSERT INTO alert_events_archive SELECT * FROM alert_events"
							+ " WHERE status IN ('SENT', 'RESOLVED') AND created_at < now() - (? || ' days')::interval"
							+ " ON CONFLICT DO NOTHING",
					Integer.toString(ALERT_ARCHIVE_DAYS));
			int purged = jdbc.update(
					"DELETE FROM alert_events WHERE status IN ('SENT', 'RESOLVED')"
							+ " AND created_at < now() - (? || ' days')::interval",
					Integer.toString(ALERT_ARCHIVE_DAYS));
			if (moved > 0 || purged > 0) {
				log.info("Alert archive roll: {} archived, {} purged from hot", moved, purged);
			}
		} catch (RuntimeException ex) {
			log.warn("Alert archive roll failed; next month retries");
		}
	}

	void purgeOldArchive() {
		try {
			int purged = jdbc.update(
					"DELETE FROM alert_events_archive WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(ARCHIVE_PURGE_DAYS));
			if (purged > 0) {
				log.warn("Purged {} alert archive rows older than {} days (reviewed policy)", purged,
						ARCHIVE_PURGE_DAYS);
			}
		} catch (RuntimeException ex) {
			log.warn("Alert archive purge failed; next month retries");
		}
	}

	void purgeOldLogs() {
		try {
			jdbc.update("DELETE FROM notification_log WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(LOG_PURGE_DAYS));
		} catch (RuntimeException ex) {
			log.warn("Notification log trim failed; next month retries");
		}
	}

	void purgeOldDedupes() {
		try {
			jdbc.update("DELETE FROM notification_dedupe WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(DEDUPE_PURGE_DAYS));
		} catch (RuntimeException ex) {
			log.warn("Notification dedupe trim failed; next month retries");
		}
	}
}
