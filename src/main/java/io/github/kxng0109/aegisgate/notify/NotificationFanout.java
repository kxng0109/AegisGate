package io.github.kxng0109.aegisgate.notify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.kxng0109.aegisgate.budget.NotificationBounceRepository;
import io.github.kxng0109.aegisgate.budget.NotificationDedupe;
import io.github.kxng0109.aegisgate.budget.NotificationDedupeRepository;
import io.github.kxng0109.aegisgate.budget.NotificationLogEntry;
import io.github.kxng0109.aegisgate.budget.NotificationLogRepository;
import io.github.kxng0109.aegisgate.budget.NotificationPreference;
import io.github.kxng0109.aegisgate.budget.NotificationPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans out delivered alerts to opt-in channels after the outbox transaction commits (so a rolled-back send
 * never notifies). Per subscription: bounce check → severity gate → dedupe claim → send → audit log row.
 * Every outcome is logged; only SENT touches the outside world.
 */
@Component
public class NotificationFanout {

	private static final Logger log = LoggerFactory.getLogger(NotificationFanout.class);

	private final NotificationPreferenceRepository preferences;

	private final NotificationBounceRepository bounces;

	private final NotificationDedupeRepository dedupes;

	private final NotificationLogRepository logRepository;

	private final Map<String, ChannelSender> senders;

	public NotificationFanout(NotificationPreferenceRepository preferences,
	                          NotificationBounceRepository bounces,
	                          NotificationDedupeRepository dedupes,
	                          NotificationLogRepository logRepository,
	                          List<ChannelSender> senders) {
		this.preferences = preferences;
		this.bounces = bounces;
		this.dedupes = dedupes;
		this.logRepository = logRepository;
		this.senders = new ConcurrentHashMap<>();
		for (ChannelSender sender : senders) {
			this.senders.put(sender.channel(), sender);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onDelivered(AlertDeliveredEvent event) {
		NotificationPayload payload = new NotificationPayload(event.scope(), event.detector(),
				event.severity(), event.startsAt(), event.value(), event.month());
		for (NotificationPreference preference : preferences.findByScope(event.scope())) {
			deliver(event, preference, payload);
		}
	}

	private void deliver(AlertDeliveredEvent event, NotificationPreference preference,
	                     NotificationPayload payload) {
		ChannelSender sender = senders.get(preference.getChannel());
		if (sender == null) {
			audit(event, preference, "SKIPPED", "unknown channel");
			return;
		}
		if (severityRank(event.severity()) < severityRank(preference.getMinSeverity())) {
			return;
		}
		if (bounces.existsByChannelAndTarget(preference.getChannel(), preference.getTarget())) {
			audit(event, preference, "SUPPRESSED", "bounced destination");
			return;
		}
		try {
			dedupes.save(new NotificationDedupe(event.dedupeSha(), preference.getChannel(),
					preference.getTarget()));
		} catch (DataIntegrityViolationException duplicate) {
			audit(event, preference, "SKIPPED", "duplicate delivery");
			return;
		}
		ChannelResult result;
		try {
			result = sender.send(preference, payload);
		} catch (RuntimeException ex) {
			log.warn("Channel send failed for {}:{}", preference.getChannel(), preference.getTarget());
			result = ChannelResult.TRANSIENT;
		}
		audit(event, preference, result == ChannelResult.SENT ? "SENT" : "FAILED", result.name());
	}

	private void audit(AlertDeliveredEvent event, NotificationPreference preference, String status,
	                   String detail) {
		try {
			logRepository.save(new NotificationLogEntry(event.dedupeSha(), event.scope(),
					preference.getChannel(), preference.getTarget(), status, detail));
		} catch (RuntimeException ex) {
			log.warn("Notification audit write failed for {}", event.dedupeSha());
		}
	}

	static int severityRank(String severity) {
		if ("critical".equalsIgnoreCase(severity)) {
			return 1;
		}
		return 0;
	}
}
