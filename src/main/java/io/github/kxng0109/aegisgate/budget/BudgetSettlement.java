package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates hold-then-settle spend accounting: admission holds, stream-end true-ups, abort handling, gap-row
 * persistence, and sweeper expiry. Thin over {@link BudgetEnforcer} (Lua) and {@link BudgetGapRepository} (audit).
 *
 * <p>Fail direction is always safe: money only ever moves inside the atomic scripts; every persistence failure
 * here re-arms work for the next tick instead of losing it, and the hold H stays counted (over-count, never
 * under-count) whenever settlement cannot complete.</p>
 */
@Service
public class BudgetSettlement {

	private static final Logger log = LoggerFactory.getLogger(BudgetSettlement.class);

	private final BudgetEnforcer enforcer;

	private final BudgetGapRepository gapRepository;

	private final BudgetSettlementProperties properties;

	public BudgetSettlement(BudgetEnforcer enforcer, BudgetGapRepository gapRepository,
	                        BudgetSettlementProperties properties) {
		this.enforcer = enforcer;
		this.gapRepository = gapRepository;
		this.properties = properties;
	}

	/**
	 * Admission under hold-then-settle. When the kill-switch is off, falls back to the prompt-only gate and
	 * reports no hold (sentinel micros {@code -1}).
	 *
	 * @return authorization carrying the verdict, the hold cost, and the admission month
	 */
	public BudgetEnforcer.HoldAuthorization authorize(SHA256Hash keyHash, @Nullable String ownerId,
	                                                  ProviderType type, String model,
	                                                  int promptChars, @Nullable Integer maxTokensOpt,
	                                                  @Nullable String idempotencyKey) {
		if (!properties.enabled()) {
			BudgetDecision decision = enforcer.checkBudget(keyHash, ownerId, type, model,
					BudgetEnforcer.estimatePromptTokens(promptChars), idempotencyKey);
			Instant now = Instant.now();
			return new BudgetEnforcer.HoldAuthorization(decision, -1L,
					YearMonth.from(now.atZone(ZoneOffset.UTC)).toString());
		}
		int maxTokens = maxTokensOpt != null && maxTokensOpt > 0
				? Math.min(maxTokensOpt, properties.maxTokensCeiling())
				: properties.maxTokensCeiling();
		return enforcer.authorizeHold(keyHash, ownerId, type, model,
				BudgetEnforcer.estimatePromptTokens(promptChars), maxTokens, idempotencyKey);
	}

	/**
	 * Creates the hold record for an admitted request. No-op when settlement is disabled.
	 *
	 * @return {@code true} when the record was created (or settlement is disabled)
	 */
	public boolean createHold(String holdId, String keyHex, @Nullable String ownerId,
	                          BudgetEnforcer.HoldAuthorization auth) {
		if (!properties.enabled() || auth.holdMicros() < 0) {
			return true;
		}
		String team = ownerId == null || ownerId.isBlank() ? "" : ownerId;
		Instant now = Instant.now();
		try {
			return enforcer.createHold(holdId, keyHex + "|" + team, auth.holdMicros(), auth.holdMonth(),
					now.getEpochSecond(), properties.holdTtlSeconds());
		} catch (RateLimitUnavailableException ex) {
			// H is already counted by admission; a missing hold record only means a later settle becomes a
			// documented gap instead of a true-up. Never fail the request for bookkeeping.
			log.warn("Hold record creation failed for hold {}; continuing without true-up", holdId);
			return false;
		}
	}

	/**
	 * Trues a hold up to measured actual spend at stream end.
	 *
	 * @param actualMicros measured actual cost A (micro dollars, >= 0)
	 * @param abort        {@code true} on client abort: settles the input-known portion and re-arms the output
	 *                     hold for the abort grace window instead of closing it
	 * @return settle outcome; a set gap flag persists a gap row (abort re-arms do not, unless straddled)
	 */
	public BudgetEnforcer.SettleOutcome settleStream(String holdId, String keyHex, @Nullable String ownerId,
	                                                 String origMonth, long actualMicros, boolean abort) {
		Instant now = Instant.now();
		String currMonth = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		long abortDue = abort ? now.getEpochSecond() + properties.abortGraceSeconds() : 0L;
		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(holdId, "KEY", keyHex, ownerId, keyHex,
				origMonth, currMonth, Math.max(0L, actualMicros), abortDue);
		if (outcome.gapSet()) {
			String reason = !origMonth.equals(currMonth) ? "ROLLOVER"
					: (abort ? "ABORTED" : "EXPIRED");
			persistGap(holdId, keyHex, outcome.amountApplied(), currMonth, origMonth, reason, actualMicros);
		}
		return outcome;
	}

	/**
	 * Expires one due hold (sweeper path): crash holds, lapsed abort grace, or records already gone.
	 *
	 * @param holdHashKey hold hash key as returned by {@link BudgetEnforcer#dueHoldKeys}
	 * @return {@code true} when the hold needs no further attention
	 */
	public boolean expireDueHold(String holdHashKey) {
		Map<Object, Object> fields = enforcer.readHold(holdHashKey);
		String holdId = holdIdOf(holdHashKey);
		String keyHex = "";
		String team = "";
		String origMonth = "";
		long held = 0L;
		String state = "";
		if (!fields.isEmpty()) {
			String subject = stringField(fields, "subject");
			int sep = subject.indexOf('|');
			keyHex = sep < 0 ? subject : subject.substring(0, sep);
			team = sep < 0 ? "" : subject.substring(sep + 1);
			origMonth = stringField(fields, "orig_month");
			held = longField(fields, "amount");
			state = stringField(fields, "state");
		}
		Instant now = Instant.now();
		String currMonth = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		BudgetEnforcer.SettleOutcome outcome;
		try {
			outcome = enforcer.settle(holdId, "KEY", keyHex.isEmpty() ? holdId : keyHex,
					team.isEmpty() ? null : team, keyHex.isEmpty() ? holdId : keyHex,
					origMonth.isEmpty() ? currMonth : origMonth, currMonth, -1L, 0L);
		} catch (RateLimitUnavailableException ex) {
			log.warn("Hold expiry Lua failed for {}; re-arming", holdHashKey);
			rearm(holdHashKey, now.getEpochSecond() + 60L);
			return false;
		}
		if (outcome.gapSet()) {
			String reason = fields.isEmpty() ? "EXPIRED" : ("ABORTED".equals(state) ? "ABORTED" : "CRASH");
			try {
				gapRepository.save(new BudgetGapRecord(holdId, "KEY", keyHex.isEmpty() ? holdId : keyHex,
						held, 0L, origMonth.isEmpty() ? currMonth : origMonth, currMonth, reason));
			} catch (RuntimeException ex) {
				log.warn("Gap-row persistence failed for {}; re-arming for retry", holdHashKey);
				rearm(holdHashKey, now.getEpochSecond() + 60L);
				return false;
			}
		}
		return true;
	}

	/**
	 * Hold ids whose expiry score has passed, bounded for one sweeper tick.
	 */
	public Set<String> dueHoldKeys(int limit) {
		return enforcer.dueHoldKeys(limit, Instant.now().getEpochSecond());
	}

	private void persistGap(String holdId, String keyHex, long applied, String currMonth, String origMonth,
	                        String reason, long settledMicros) {
		try {
			gapRepository.save(new BudgetGapRecord(holdId, "KEY", keyHex, applied, settledMicros,
					origMonth, currMonth, reason));
		} catch (RuntimeException ex) {
			// Audit-only: the money already moved atomically; a lost gap row degrades chargeback detail,
			// never correctness of the counters.
			log.warn("Gap-row persistence failed for hold {}", holdId);
		}
	}

	private void rearm(String holdHashKey, long scoreEpochSec) {
		try {
			enforcer.rearmHold(holdHashKey, scoreEpochSec);
		} catch (RateLimitUnavailableException ex) {
			log.warn("Hold re-arm failed for {}; entry retries on TTL drift", holdHashKey);
		}
	}

	private static String holdIdOf(String holdHashKey) {
		int idx = holdHashKey.lastIndexOf(':');
		return idx < 0 ? holdHashKey : holdHashKey.substring(idx + 1);
	}

	private static String stringField(Map<Object, Object> fields, String name) {
		Object value = fields.get(name);
		return value == null ? "" : value.toString();
	}

	private static long longField(Map<Object, Object> fields, String name) {
		try {
			return Long.parseLong(stringField(fields, name));
		} catch (NumberFormatException malformed) {
			return 0L;
		}
	}
}
