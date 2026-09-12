package io.github.kxng0109.aegisgate.budget;

import io.github.kxng0109.aegisgate.contracts.BootstrapKey;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owns hard spend budgets: durable limits in PostgreSQL, enforcement copies in Redis, audit for every mutation.
 *
 * <p>Hot-path enforcement never touches this service (the {@link BudgetEnforcer} reads Redis only). This service
 * runs at admin time and at startup: CRUD writes through to Redis config hashes and invalidates the enforcer presence
 * set, so limit changes bite immediately on this instance and within the presence TTL elsewhere. Startup backfill is
 * best-effort by design (a failure logs and continues; unknown subjects call the script until cached, so correctness
 * never depends on the backfill succeeding).</p>
 */
@Slf4j
@Service
public class BudgetService {

	/**
	 * Levels in enforcement precedence order.
	 */
	public static final List<String> LEVELS = List.of("KEY", "TEAM", "ORG");

	private static final Pattern SUBJECT_PATTERN = Pattern.compile("[a-z0-9-]{1,128}");

	private static final Pattern HEX64_PATTERN = Pattern.compile("[0-9a-f]{64}");

	private static final String ADMIN_ACTOR = "admin";

	private final BudgetLimitRepository limits;

	private final BudgetAuditRepository audits;

	private final StringRedisTemplate redisTemplate;

	private final BudgetEnforcer enforcer;

	private final GatewayProperties gatewayProperties;

	private final SsrfValidator ssrfValidator;

	private final BudgetChangeNotifier notifier;

	public BudgetService(
			BudgetLimitRepository limits,
			BudgetAuditRepository audits,
			StringRedisTemplate redisTemplate,
			BudgetEnforcer enforcer,
			GatewayProperties gatewayProperties,
			SsrfValidator ssrfValidator,
			BudgetChangeNotifier notifier
	) {
		this.limits = limits;
		this.audits = audits;
		this.redisTemplate = redisTemplate;
		this.enforcer = enforcer;
		this.gatewayProperties = gatewayProperties;
		this.ssrfValidator = ssrfValidator;
		this.notifier = notifier;
	}

	private static String snapshot(BudgetLimit limit) {
		return "{\"level\":\"" + limit.getLevel() + "\",\"subject\":\"" + limit.getSubjectId()
				+ "\",\"minuteMicros\":" + limit.getMinuteMicros()
				+ ",\"monthMicros\":" + limit.getMonthMicros() + "}";
	}

	static void requireLevel(String level) {
		if (!LEVELS.contains(level)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level must be one of KEY, TEAM, ORG");
		}
	}

	static void requireSubject(String level, String subject) {
		if (subject == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject must not be blank");
		}
		boolean ok = "KEY".equals(level)
				? HEX64_PATTERN.matcher(subject).matches()
				: SUBJECT_PATTERN.matcher(subject).matches();
		if (!ok) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"subject must be key hex (KEY) or [a-z0-9-] (TEAM/ORG)"
			);
		}
	}

	private static void requireNonNegative(long value, String field) {
		if (value < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be non-negative");
		}
	}

	/**
	 * Creates one cap. Duplicate (level, subject) is a 409, not an upsert — silent overwrites of money limits are a
	 * footgun.
	 */
	@Transactional
	public BudgetLimit create(String level, String subject, long minuteMicros, long monthMicros,
	                          @Nullable String webhookUrl) {
		requireLevel(level);
		requireSubject(level, subject);
		requireNonNegative(minuteMicros, "minuteMicros");
		requireNonNegative(monthMicros, "monthMicros");
		String webhook = validateWebhook(webhookUrl);
		if (limits.findByLevelAndSubjectId(level, subject).isPresent()) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"budget already exists for " + level + "/" + subject
			);
		}
		BudgetLimit limit = new BudgetLimit(level, subject, minuteMicros, monthMicros, webhook);
		try {
			limit = limits.saveAndFlush(limit);
		} catch (DataIntegrityViolationException duplicate) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"budget already exists for " + level + "/" + subject
			);
		}
		publishConfigAfterCommit(limit);
		audits.save(new BudgetAuditRecord(ADMIN_ACTOR, "CREATE", level, subject, null, snapshot(limit)));
		return limit;
	}

	/**
	 * Replaces the money fields of one cap. Optimistic locking fails concurrent edits loudly (409).
	 */
	@Transactional
	public BudgetLimit update(UUID id, long minuteMicros, long monthMicros, @Nullable String webhookUrl) {
		requireNonNegative(minuteMicros, "minuteMicros");
		requireNonNegative(monthMicros, "monthMicros");
		String webhook = validateWebhook(webhookUrl);
		BudgetLimit limit = limits.findById(id).orElseThrow(() ->
				                                                    new ResponseStatusException(
						                                                    HttpStatus.NOT_FOUND,
						                                                    "budget not found"
				                                                    ));
		String before = snapshot(limit);
		try {
			limit.update(minuteMicros, monthMicros, webhook);
			limit = limits.saveAndFlush(limit);
		} catch (ObjectOptimisticLockingFailureException conflict) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "budget changed concurrently, retry");
		}
		publishConfigAfterCommit(limit);
		audits.save(new BudgetAuditRecord(
				ADMIN_ACTOR, "UPDATE", limit.getLevel(), limit.getSubjectId(),
				before, snapshot(limit)
		));
		return limit;
	}

	/**
	 * Deletes one cap, snapshotting live spend into the audit row so chargeback history survives the subject.
	 */
	@Transactional
	public void delete(UUID id) {
		BudgetLimit limit = limits.findById(id).orElseThrow(() ->
				                                                    new ResponseStatusException(
						                                                    HttpStatus.NOT_FOUND,
						                                                    "budget not found"
				                                                    ));
		String after = snapshotWithSpend(limit);
		limits.delete(limit);
		dropConfigAfterCommit(limit.getLevel(), limit.getSubjectId());
		audits.save(new BudgetAuditRecord(
				ADMIN_ACTOR, "DELETE", limit.getLevel(), limit.getSubjectId(),
				snapshot(limit), after
		));
	}

	/**
	 * Reads one cap plus live counters. Counter reads are best-effort; missing counters read as zero spend.
	 */
	@Transactional(readOnly = true)
	public BalanceView balance(String level, String subject) {
		requireLevel(level);
		Optional<BudgetLimit> found = limits.findByLevelAndSubjectId(level, subject);
		long minuteLimit = found.map(BudgetLimit::getMinuteMicros).orElse(0L);
		long monthLimit = found.map(BudgetLimit::getMonthMicros).orElse(0L);
		Instant now = Instant.now();
		long epochMinute = now.toEpochMilli() / 60_000L;
		String month = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		long minuteSpent = readCounter(BudgetEnforcer.minuteKey(level, subject, epochMinute));
		long monthSpent = readCounter(BudgetEnforcer.monthKey(level, subject, month));
		return new BalanceView(level, subject, minuteLimit, minuteSpent, monthLimit, monthSpent);
	}

	/**
	 * Mirrors every durable limit into Redis config hashes and warms presence. Best-effort: failures log and continue,
	 * because enforcement correctness never depends on this having run (unknown subjects call the script, which reads
	 * config live). Runs at startup as the crash reconciler: any config write lost to a pre-commit crash is restored
	 * here, since Redis writes only happen after commit and never before it.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void backfill() {
		try {
			for (BudgetLimit limit : limits.findAll()) {
				writeConfig(limit);
				markPresence(limit);
			}
		} catch (RuntimeException ex) {
			log.warn("Budget backfill incomplete, enforcement falls back to live config reads: {}", ex.getMessage());
		}
	}

	private void writeConfig(BudgetLimit limit) {
		redisTemplate.opsForHash().putAll(
				BudgetEnforcer.cfgKey(limit.getLevel(), limit.getSubjectId()),
				Map.of(
						"minute_micros", Long.toString(limit.getMinuteMicros()),
						"month_micros", Long.toString(limit.getMonthMicros())
				)
		);
	}

	/**
	 * Publishes one limit to Redis only after the surrounding database transaction commits. Publishing inside the
	 * transaction would leave phantom caps (or phantom deletions) in Redis when the transaction rolls back; the
	 * startup backfill restores anything lost the other way. Outside a transaction (plain unit-test calls) the
	 * write runs synchronously.
	 */
	private void publishConfigAfterCommit(BudgetLimit limit) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					writeConfig(limit);
					enforcer.invalidateAll();
					notifier.notifyChanged(limit.getLevel(), limit.getSubjectId());
				}
			});
		} else {
			writeConfig(limit);
			enforcer.invalidateAll();
			notifier.notifyChanged(limit.getLevel(), limit.getSubjectId());
		}
	}

	/**
	 * Drops one limit from Redis only after the surrounding database transaction commits (see
	 * {@link #publishConfigAfterCommit} for why).
	 */
	private void dropConfigAfterCommit(String level, String subject) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					redisTemplate.delete(BudgetEnforcer.cfgKey(level, subject));
					enforcer.invalidateAll();
					notifier.notifyChanged(level, subject);
				}
			});
		} else {
			redisTemplate.delete(BudgetEnforcer.cfgKey(level, subject));
			enforcer.invalidateAll();
			notifier.notifyChanged(level, subject);
		}
	}

	private void markPresence(BudgetLimit limit) {
		if ("KEY".equals(limit.getLevel())) {
			enforcer.markBudgeted(limit.getSubjectId());
			return;
		}
		if ("ORG".equals(limit.getLevel())) {
			for (BootstrapKey key : gatewayProperties.getBootstrapKeys()) {
				if (key.plaintextKey() != null && !key.plaintextKey().isBlank()) {
					enforcer.markBudgeted(SHA256Hash.fromRawKey(key.plaintextKey()).hex());
				}
			}
			return;
		}
		for (BootstrapKey key : gatewayProperties.getBootstrapKeys()) {
			if (limit.getSubjectId().equals(key.ownerId())
					&& key.plaintextKey() != null && !key.plaintextKey().isBlank()) {
				enforcer.markBudgeted(SHA256Hash.fromRawKey(key.plaintextKey()).hex());
			}
		}
	}

	private long readCounter(String key) {
		try {
			String value = redisTemplate.opsForValue().get(key);
			return value == null ? 0L : Math.max(0L, Long.parseLong(value.trim()));
		} catch (RuntimeException ex) {
			throw new ResponseStatusException(
					HttpStatus.SERVICE_UNAVAILABLE, "Budget counters unavailable", ex);
		}
	}

	private String snapshotWithSpend(BudgetLimit limit) {
		Instant now = Instant.now();
		long epochMinute = now.toEpochMilli() / 60_000L;
		String month = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		long minuteSpent = readCounter(BudgetEnforcer.minuteKey(limit.getLevel(), limit.getSubjectId(), epochMinute));
		long monthSpent = readCounter(BudgetEnforcer.monthKey(limit.getLevel(), limit.getSubjectId(), month));
		return snapshot(limit) + ",\"minuteSpent\":" + minuteSpent + ",\"monthSpent\":" + monthSpent + "}";
	}

	private @Nullable String validateWebhook(@Nullable String webhookUrl) {
		if (webhookUrl == null || webhookUrl.isBlank()) {
			return null;
		}
		final URI uri;
		try {
			uri = URI.create(webhookUrl.trim());
		} catch (IllegalArgumentException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhookUrl is malformed");
		}
		try {
			ssrfValidator.validate(uri);
		} catch (SsrfViolationException violation) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"webhookUrl not permitted: " + violation.getMessage()
			);
		}
		return uri.toString();
	}

	/**
	 * Balance snapshot: limits plus live spend. Never throws for missing data (zeros throughout).
	 */
	public record BalanceView(
			String level,
			String subject,
			long minuteLimitMicros,
			long minuteSpentMicros,
			long monthLimitMicros,
			long monthSpentMicros
	) {
	}
}
