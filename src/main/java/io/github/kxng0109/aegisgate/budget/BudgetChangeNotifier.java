package io.github.kxng0109.aegisgate.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans budget-config changes out to every gateway instance via Postgres
 * {@code NOTIFY}. Each pod's {@link BudgetConfigInvalidationListener} drops its
 * process-local presence entries on receipt, so a remotely created/changed cap
 * stops admitting unstamped spend within milliseconds instead of waiting out the
 * negative-cache TTL.
 *
 * <p>Best-effort by design: enforcement never depends on fan-out (unknown subjects
 * call the script, which reads config live; negatives lapse in 5s regardless).
 * Failures log and continue. The channel payload is {@code LEVEL:subject}; both
 * halves are server-validated before this point ({@code requireLevel} plus the
 * subject pattern), so no quote characters can reach the statement — and the
 * listener treats any malformed payload as invalidate-all rather than trusting it.
 */
@Slf4j
@Component
public class BudgetChangeNotifier {

	static final String CHANNEL = "budget_config_changed";

	private final JdbcTemplate jdbc;

	public BudgetChangeNotifier(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param level KEY, TEAM, or ORG (validated upstream)
	 * @param subject key hex, owner slug, or global scope (validated upstream)
	 */
	public void notifyChanged(String level, String subject) {
		try {
			jdbc.execute("SELECT pg_notify('" + CHANNEL + "', '" + level + ":" + subject + "')");
		} catch (RuntimeException ex) {
			log.warn("Budget config fan-out failed, remotes heal via TTL: {}", ex.getMessage());
		}
	}
}
