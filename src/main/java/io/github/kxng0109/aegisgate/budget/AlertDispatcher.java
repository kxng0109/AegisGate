package io.github.kxng0109.aegisgate.budget;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.github.kxng0109.aegisgate.notify.AlertDeliveredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers {@code alert_events} outbox rows to Alertmanager. Claims due rows with {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} (concurrent dispatchers partition work), POSTs each as a single-element v2 array batch, and
 * marks SENT. Retryable failures back off with Full Jitter; rows past the attempt ceiling are marked FAILED
 * (poison, kept for audit); terminal rejections die immediately. Single-flight per tick via advisory lock.
 */
@Component
public class AlertDispatcher {

	static final String LOCK_NAME = "aegis-alert-dispatcher";

	static final int MAX_ATTEMPTS = 10;

	private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

	private final AlertEventRepository outbox;

	private final AlertmanagerClient client;

	private final BudgetDetectionProperties properties;

	private final DataSource dataSource;

	private final ApplicationEventPublisher eventPublisher;

	public AlertDispatcher(AlertEventRepository outbox, AlertmanagerClient client,
	                       BudgetDetectionProperties properties, DataSource dataSource,
	                       ApplicationEventPublisher eventPublisher) {
		this.outbox = outbox;
		this.client = client;
		this.properties = properties;
		this.dataSource = dataSource;
		this.eventPublisher = eventPublisher;
	}

	@Scheduled(fixedDelayString = "${gateway.budget.detection.dispatch-interval:30s}")
	public void dispatch() {
		if (!properties.enabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			if (!AdvisoryLock.tryLock(connection, LOCK_NAME)) {
				return;
			}
			try {
				dispatchClaimed(Instant.now());
			} finally {
				AdvisoryLock.unlock(connection, LOCK_NAME);
			}
		} catch (SQLException ex) {
			log.warn("Alert dispatch tick skipped (datasource unavailable)");
		} catch (RuntimeException ex) {
			log.warn("Alert dispatch tick failed; next interval retries");
		}
	}

	@Transactional
	void dispatchClaimed(Instant now) {
		List<AlertEvent> due = outbox.claimDue(now, properties.dispatchBatch());
		for (AlertEvent event : due) {
			try {
				Map<String, Object> payload = Map.of(
						"labels", Map.of(
								"alertname", "AegisBudget" + capitalize(event.getDetector()),
								"scope", event.getScope(),
								"detector", event.getDetector(),
								"severity", event.getSeverity()),
						"annotations", Map.of("summary", event.getPayload()),
						"startsAt", event.getStartsAt().toString());
				AlertmanagerClient.PostResult result = client.post(List.of(payload));
				if (result.sent()) {
					event.markSent();
				} else if (!result.retryable() || event.getAttempts() + 1 >= MAX_ATTEMPTS) {					event.markDead();
				} else {
					event.backoff(now.plusMillis(
							AlertmanagerClient.backoffDelayMillis(event.getAttempts() + 1)));
				}
				outbox.save(event);
				if ("SENT".equals(event.getStatus())) {
					// Delivered after this transaction commits (listener phase), so a rolled-back send
					// never notifies.
					eventPublisher.publishEvent(new AlertDeliveredEvent(
							event.getDedupeSha(), event.getScope(), event.getDetector(),
							event.getSeverity(), event.getStartsAt(),
							event.getValueText() == null ? "" : event.getValueText(),
							event.getMonth() == null ? "" : event.getMonth()));
				}
			} catch (RuntimeException ex) {
				log.warn("Alert delivery failed for {}; leaving for retry", event.getDedupeSha());
			}
		}
	}

	private static String capitalize(String detector) {
		if (detector == null || detector.isEmpty()) {
			return "Unknown";
		}
		StringBuilder name = new StringBuilder();
		boolean upper = true;
		for (char c : detector.toCharArray()) {
			if (c == '_') {
				upper = true;
			} else if (upper) {
				name.append(Character.toUpperCase(c));
				upper = false;
			} else {
				name.append(c);
			}
		}
		return name.toString();
	}
}
