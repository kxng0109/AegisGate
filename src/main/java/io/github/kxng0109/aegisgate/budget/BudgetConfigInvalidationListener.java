package io.github.kxng0109.aegisgate.budget;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drops process-local budget presence on remote config changes. Holds one dedicated
 * Postgres connection running {@code LISTEN} on a daemon virtual thread; each
 * notification invalidates exactly what changed — a {@code KEY} payload drops that
 * key's entry via the now-live single-key path, anything else (TEAM/ORG scope,
 * malformed payload, unknown level) drops the whole set, because over-invalidation
 * only costs one authoritative script call while under-invalidation admits spend.
 *
 * <p>Holds one pool slot for its lifetime (documented tradeoff; the pool sizes for
 * it). Reconnects with backoff on any failure; a dead listener degrades to the 5s
 * negative TTL, never to unbounded staleness.
 */
@Slf4j
@Component
public class BudgetConfigInvalidationListener {

	private final DataSource dataSource;
	private final BudgetEnforcer enforcer;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile Thread listenerThread;

	public BudgetConfigInvalidationListener(DataSource dataSource, BudgetEnforcer enforcer) {
		this.dataSource = dataSource;
		this.enforcer = enforcer;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (running.compareAndSet(false, true)) {
			listenerThread = Thread.ofVirtual().name("budget-invalidation-listener").start(this::listen);
		}
	}

	/**
	 * Visible for tests: routes one batch of notifications to the presence cache.
	 */
	static void dispatch(BudgetEnforcer enforcer, PGNotification[] notifications) {
		if (notifications == null) {
			return;
		}
		for (PGNotification notification : notifications) {
			String payload = notification == null ? "" : notification.getParameter();
			int colon = payload.indexOf(':');
			if (colon > 0 && payload.startsWith("KEY:")) {
				enforcer.invalidate(payload.substring(colon + 1));
			} else {
				enforcer.invalidateAll();
			}
		}
	}

	private void listen() {
		while (running.get()) {
			try (Connection connection = dataSource.getConnection()) {
				PGConnection pg = connection.unwrap(PGConnection.class);
				try (Statement statement = connection.createStatement()) {
					statement.execute("LISTEN " + BudgetChangeNotifier.CHANNEL);
				}
				while (running.get()) {
					dispatch(enforcer, pg.getNotifications(10_000));
				}
			} catch (SQLException | RuntimeException ex) {
				if (!running.get()) {
					return;
				}
				log.warn("Budget invalidation listener reconnecting: {}", ex.getMessage());
				try {
					Thread.sleep(5_000);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/**
	 * Visible for tests: stops the loop; the blocked connection unwinds on close.
	 */
	void stop() {
		running.set(false);
		Thread thread = listenerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}
}
