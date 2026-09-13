package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-pod single-flight via session-level PostgreSQL advisory locks. The lock must be acquired and released
 * on the SAME JDBC connection (a pooled {@code JdbcTemplate} call per statement could lock on one session and
 * unlock on another, leaking the lock) — callers hold one {@link Connection} for the whole critical section.
 *
 * <p>Locks live in the session, not the table: a pod crash or failover releases them implicitly, so a dead
 * leader can never wedge followers. All coordinated work must therefore be idempotent (it will occasionally
 * run twice across a failover).</p>
 */
public final class AdvisoryLock {

	private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);

	private AdvisoryLock() {
	}

	/**
	 * Tries to acquire the named lock without waiting.
	 *
	 * @param connection live JDBC connection held for the whole critical section
	 * @param name       lock name (hashed to 64 bits; distinct names never contend)
	 * @return {@code true} when this pod holds the lock
	 */
	public static boolean tryLock(Connection connection, String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
			statement.setString(1, name);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getBoolean(1);
			}
		}
	}

	/**
	 * Releases the named lock. Best-effort: a failed unlock only leaks until the session closes.
	 */
	public static void unlock(Connection connection, String name) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
			statement.setString(1, name);
			statement.execute();
		} catch (SQLException ex) {
			log.warn("Advisory unlock failed for '{}' (lock lapses with the session)", name);
		}
	}
}
