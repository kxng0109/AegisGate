package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires due settlement holds: crashed streams, lapsed abort grace, and records already gone. Single-flight
 * across pods via a session-level PostgreSQL advisory lock held on one dedicated connection for the whole sweep
 * (a pooled JdbcTemplate call per statement could lock on one session and unlock on another, leaking the lock).
 *
 * <p>Idempotent by construction: expiry runs the settle script's expire branch (settled-flag claim makes a racing
 * late settle a replay), and gap rows are insert-only. A tick that fails partway re-arms entries for the next
 * tick; nothing is ever dropped.</p>
 */
@Component
public class BudgetHoldSweeper {

	private static final Logger log = LoggerFactory.getLogger(BudgetHoldSweeper.class);

	static final String LOCK_NAME = "aegis-budget-hold-sweeper";

	private final DataSource dataSource;

	private final BudgetSettlement settlement;

	private final BudgetSettlementProperties properties;

	public BudgetHoldSweeper(DataSource dataSource, BudgetSettlement settlement,
	                         BudgetSettlementProperties properties) {
		this.dataSource = dataSource;
		this.settlement = settlement;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${gateway.budget.settlement.sweep-interval:30s}")
	public void sweep() {
		if (!properties.enabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			if (!AdvisoryLock.tryLock(connection, LOCK_NAME)) {
				return;
			}
			try {
				int budget = properties.sweeperBatch();
				for (String holdHashKey : settlement.dueHoldKeys(budget)) {
					try {
						settlement.expireDueHold(holdHashKey);
					} catch (RuntimeException ex) {
						log.warn("Hold expiry failed for {}; continuing sweep", holdHashKey);
					}
					if (--budget <= 0) {
						break;
					}
				}
			} finally {
				AdvisoryLock.unlock(connection, LOCK_NAME);
			}
		} catch (SQLException ex) {
			log.warn("Hold sweeper tick skipped (datasource unavailable)");
		}
	}
}
