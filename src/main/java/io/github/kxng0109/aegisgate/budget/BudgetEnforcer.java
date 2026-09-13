package io.github.kxng0109.aegisgate.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces hard spend budgets against the atomic {@code budget_limit.lua} script: exactly one Redis round trip per
 * budgeted request, zero database reads, zero overhead for unbudgeted keys.
 *
 * <p>Levels are KEY (key sha256 hex), TEAM (owner id), ORG (the single global scope), checked in that precedence.
 * Windows are rolling 60 seconds plus UTC calendar months; month rollover is implicit (a new {@code YYYY-MM} key each
 * month, 45-day safety TTL), so there is no reset job to race. Denied requests consume nothing (check-before-increment
 * inside the script).</p>
 *
 * <p>Presence fast-path: a bounded Caffeine set remembers which keys have any budget. Unknown keys call the script
 * (safe default — a new budget bites immediately); confirmed-unbudgeted keys skip it for 60 seconds (zero extra round
 * trips for the common case). Admin CRUD invalidates exactly (single key) or broadly (team/org/global, via
 * {@link #invalidateAll}), so cross-instance staleness is bounded by the TTL and can only delay enforcement, never
 * corrupt it. Fail-closed: any Redis failure raises {@link RateLimitUnavailableException} (deny for every key class,
 * including unlimited).</p>
 */
@Service
public class BudgetEnforcer {

	/**
	 * Subject id for the single global org scope.
	 */
	public static final String GLOBAL_ORG = "global";

	/**
	 * Single-slot tag shared by every budget key. Redis Cluster routes a multi-key Lua script to one slot only
	 * when all its keys share a hash tag; without it the 9-key gate dies with CROSSSLOT the day the fleet moves
	 * to Cluster. One tag for the whole invocation (not per level) preserves the script's atomic
	 * check-before-increment across KEY/TEAM/ORG — per-level tags would scatter the levels across slots and
	 * reintroduce the TOCTOU the script exists to prevent. Cutover note: pre-tag counters are abandoned, not
	 * migrated (minute counters TTL out in 60s; month counters restart — acceptable because budgets ship
	 * unreleased with no live spend to preserve).
	 */
	private static final String SLOT_TAG = "{b:" + GLOBAL_ORG + "}";

	/**
	 * Largest integer exactly representable as a Lua number. Estimates above this can neither be compared nor
	 * accumulated safely inside the script; the engine clamps to this bound and the script denies anything above
	 * it without consuming.
	 */
	static final long MAX_EXACT_LUA_INTEGER = 9_007_199_254_740_991L;

	private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

	/**
	 * Negative-cached (unbudgeted) TTL. A stale negative admits spend without a gate until it lapses, so it is
	 * deliberately short: worst-case overspend per key per pod is bounded by
	 * {@code arrival_rate × 5s × avg_cost}, and cross-pod invalidation (pg_notify fan-out) closes it sooner.
	 * Positive presence keeps the 60s TTL: it never skips the script, so staleness cannot admit anything.
	 */
	private static final Duration NEGATIVE_TTL = Duration.ofSeconds(5);

	private final StringRedisTemplate redisTemplate;

	private final DefaultRedisScript<List> budgetScript;

	private final DefaultRedisScript<List> holdScript;

	private final DefaultRedisScript<List> settleScript;

	private final CostCalculator costCalculator;

	private final MeterRegistry meterRegistry;

	private final Cache<String, Boolean> budgetedKeys;

	/**
	 * Settle outcomes, mirroring {@code settle.lua} positions {@code [1]} (settled flag) and {@code [2]} (outcome).
	 */
	public static final int SETTLE_OK = 0;
	public static final int SETTLE_REPLAY = 1;
	public static final int SETTLE_ABORTED = 2;
	public static final int SETTLE_EXPIRED = 3;

	/**
	 * Admission result plus the hold cost, so the controller can create the hold record without recomputing.
	 *
	 * @param decision   gate verdict for H
	 * @param holdMicros hold cost H in micro dollars
	 * @param holdMonth  admission month {@code YYYY-MM} (rollover detection compares at settle)
	 */
	public record HoldAuthorization(BudgetDecision decision, long holdMicros, String holdMonth) {
	}

	/**
	 * Parsed {@code settle.lua} result.
	 *
	 * @param settled          whether this call moved money or claimed first
	 * @param outcome          one of {@code SETTLE_OK/REPLAY/ABORTED/EXPIRED}
	 * @param amountApplied    actual micros applied
	 * @param remainingMonthly KEY-level monthly remaining, -1 when unavailable
	 * @param gapSet           whether the gap marker was set (a gap row must be persisted)
	 */
	public record SettleOutcome(boolean settled, int outcome, long amountApplied, long remainingMonthly,
	                            boolean gapSet) {
	}

	/**
	 * @param redisTemplate  Redis access for the atomic scripts
	 * @param budgetScript   the {@code budget_limit.lua} script bean
	 * @param holdScript     the {@code hold.lua} script bean
	 * @param settleScript   the {@code settle.lua} script bean
	 * @param costCalculator prompt-side price estimates (catalog-backed, no I/O beyond Caffeine)
	 * @param meterRegistry  metrics registry (optional; isolated fallback when null)
	 */
	@Autowired
	public BudgetEnforcer(
			StringRedisTemplate redisTemplate,
			@Qualifier("budgetLimitScript") DefaultRedisScript<List> budgetScript,
			@Qualifier("budgetHoldScript") DefaultRedisScript<List> holdScript,
			@Qualifier("budgetSettleScript") DefaultRedisScript<List> settleScript,
			CostCalculator costCalculator,
			@Nullable MeterRegistry meterRegistry
	) {
		this.redisTemplate = redisTemplate;
		this.budgetScript = budgetScript;
		this.holdScript = holdScript;
		this.settleScript = settleScript;
		this.costCalculator = costCalculator;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
		this.budgetedKeys = Caffeine.newBuilder()
		                            .maximumSize(10_000)
		                            .expireAfter(presenceExpiry())
		                            .build();
	}

	/**
	 * Per-entry TTL policy: budgeted presence lives 60s (it never skips the script), unbudgeted negatives only
	 * 5s (they do skip, so the stale window must stay small).
	 */
	static Expiry<String, Boolean> presenceExpiry() {
		return new Expiry<>() {
			@Override
			public long expireAfterCreate(String key, Boolean budgeted, long currentTime) {
				return (Boolean.TRUE.equals(budgeted) ? PRESENCE_TTL : NEGATIVE_TTL).toNanos();
			}

			@Override
			public long expireAfterUpdate(String key, Boolean budgeted, long currentTime,
			                              long currentDuration) {
				return expireAfterCreate(key, budgeted, currentTime);
			}

			@Override
			public long expireAfterRead(String key, Boolean budgeted, long currentTime,
			                            long currentDuration) {
				return currentDuration;
			}
		};
	}

	static String cfgKey(String level, String subject) {
		return "budget:" + SLOT_TAG + ":cfg:" + level + ":" + subject;
	}

	/**
	 * Heuristic prompt-token estimate from a character count (~4 chars per token for typical text). Deliberately
	 * approximate: the ledger holds post-hoc truth, while this estimate only gates admission. It errs toward neither
	 * side systematically enough to matter at cap scale, and the tolerance is documented, not hidden.
	 *
	 * @param charCount characters of prompt text
	 * @return estimated prompt tokens, at least 1
	 */
	public static int estimatePromptTokens(int charCount) {
		if (charCount <= 0) {
			return 1;
		}
		// (Integer.MAX_VALUE + 3) / 4 fits comfortably in an int, so no clamp is needed.
		return (int) ((charCount + 3L) / 4L);
	}

	static String minuteKey(String level, String subject, long epochMinute) {
		return "budget:" + SLOT_TAG + ":" + level + ":" + subject + ":minute:" + epochMinute;
	}

	static String monthKey(String level, String subject, String yearMonth) {
		return "budget:" + SLOT_TAG + ":" + level + ":" + subject + ":month:" + yearMonth;
	}

	static String holdKey(String holdId) {
		return "budget:" + SLOT_TAG + ":hold:" + holdId;
	}

	static String settledKey(String holdId) {
		return "budget:" + SLOT_TAG + ":settled:" + holdId;
	}

	static String gapKey(String level, String subject, String yearMonth) {
		return "budget:" + SLOT_TAG + ":gap:" + level + ":" + subject + ":" + yearMonth;
	}

	static String holdExpiryKey() {
		return "budget:" + SLOT_TAG + ":hold-expiry";
	}

	static long secondsToMonthEnd() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime end = YearMonth.from(now)
		                             .atEndOfMonth()
		                             .plusDays(1)
		                             .atStartOfDay()
		                             .atZone(ZoneOffset.UTC);
		return Math.max(1L, end.toEpochSecond() - now.toEpochSecond());
	}

	private static List<Long> assertFive(@Nullable List<?> result) {
		if (result == null || result.size() != 5) {
			throw new RateLimitUnavailableException("Budget script returned an unexpected shape");
		}
		List<Long> checked = new ArrayList<>(5);
		for (Object value : result) {
			// StringRedisTemplate deserializes Lua integers as Strings on some paths;
			// accept both wire forms, fail closed on anything else.
			if (value instanceof Number number) {
				checked.add(number.longValue());
			} else if (value instanceof String text) {
				try {
					checked.add(Long.parseLong(text.trim()));
				} catch (NumberFormatException malformed) {
					throw new RateLimitUnavailableException("Budget script returned a non-numeric value");
				}
			} else {
				throw new RateLimitUnavailableException("Budget script returned an unexpected value");
			}
		}
		return checked;
	}

	/**
	 * Checks spend budgets for one request. Skips the Redis round trip entirely for keys known to be unbudgeted.
	 *
	 * @param keyHash         key digest (scopes the KEY level + presence entry)
	 * @param ownerId         authenticated tenant, possibly {@code null} (skips the TEAM level)
	 * @param type            provider type for the price estimate
	 * @param model           model id for the price estimate
	 * @param estimatedTokens prompt tokens the request is expected to add
	 * @param idempotencyKey client-minted idempotency key, or {@code null} when the caller has none. A retried key
	 *                       is admitted without double-debiting (the script claims it first); {@code null} skips the
	 *                       claim (internal callers such as the semantic-cache warmer).
	 * @return allowed with remaining spend, or denied with the binding level, window, and retry horizon
	 * @throws RateLimitUnavailableException when Redis is unreachable (fail-closed for every key class)
	 */
	public BudgetDecision checkBudget(
			SHA256Hash keyHash, @Nullable String ownerId,
			ProviderType type, String model, int estimatedTokens, @Nullable String idempotencyKey
	) {
		String hex = keyHash.hex();
		Boolean known = budgetedKeys.getIfPresent(hex);
		if (Boolean.FALSE.equals(known)) {
			recordDecision("allowed", "none");
			return new BudgetDecision.Allowed(-1L, 0L);
		}
		long estimatedMicros = estimateCostMicros(type, model, estimatedTokens);
		Instant now = Instant.now();
		return decide(hex, ownerId, estimatedMicros, idempotencyKey,
				now.toEpochMilli() / 60_000L, YearMonth.from(now.atZone(ZoneOffset.UTC)).toString());
	}

	/**
	 * Admission for hold-then-settle: charges the hold {@code H = prompt + maxTokens × output} through the same
	 * atomic gate, so the response body for H is identical to a prompt-only charge of the same size. The caller
	 * creates the hold record next; if it never does (crash in between), H stays counted — the safe over-count
	 * direction, surfaced as a gap only when a hold record exists but never settles.
	 *
	 * @param keyHash        key digest (scopes the KEY level + presence entry)
	 * @param ownerId        authenticated tenant, possibly {@code null} (skips the TEAM level)
	 * @param type           provider type for the price estimate
	 * @param model          model id for the price estimate
	 * @param promptTokens   measured prompt tokens
	 * @param maxTokens      server-side effective output bound (already ceiled by the caller)
	 * @param idempotencyKey client-minted idempotency key, or {@code null}
	 * @return gate verdict plus the hold cost and admission month
	 * @throws RateLimitUnavailableException when Redis is unreachable (fail-closed for every key class)
	 */
	public HoldAuthorization authorizeHold(
			SHA256Hash keyHash, @Nullable String ownerId,
			ProviderType type, String model, int promptTokens, int maxTokens, @Nullable String idempotencyKey
	) {
		String hex = keyHash.hex();
		Boolean known = budgetedKeys.getIfPresent(hex);
		if (Boolean.FALSE.equals(known)) {
			recordDecision("allowed", "none");
			Instant now = Instant.now();
			return new HoldAuthorization(new BudgetDecision.Allowed(-1L, 0L), 0L,
					YearMonth.from(now.atZone(ZoneOffset.UTC)).toString());
		}
		long holdMicros = holdCostMicros(type, model, promptTokens, maxTokens);
		Instant now = Instant.now();
		String month = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		BudgetDecision decision = decide(hex, ownerId, holdMicros, idempotencyKey,
				now.toEpochMilli() / 60_000L, month);
		return new HoldAuthorization(decision, holdMicros, month);
	}

	private BudgetDecision decide(String hex, @Nullable String ownerId, long micros,
	                              @Nullable String idempotencyKey, long epochMinute, String month) {
		String team = ownerId == null || ownerId.isBlank() ? "" : ownerId;
		List<String> keys = List.of(
				minuteKey("KEY", hex, epochMinute),
				team.isEmpty() ? "" : minuteKey("TEAM", team, epochMinute),
				minuteKey("ORG", GLOBAL_ORG, epochMinute),
				monthKey("KEY", hex, month),
				team.isEmpty() ? "" : monthKey("TEAM", team, month),
				monthKey("ORG", GLOBAL_ORG, month),
				cfgKey("KEY", hex),
				team.isEmpty() ? "" : cfgKey("TEAM", team),
				cfgKey("ORG", GLOBAL_ORG)
		);
		List<Long> result;
		try {
			result = assertFive(redisTemplate.execute(
					budgetScript, keys, Long.toString(micros),
					idempotencyKey == null ? "" : idempotencyKey));
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget service unavailable", ex);
		}
		long allowed = result.get(0);
		long rejected = result.get(1);
		long remaining = result.get(2);
		long reset = result.get(3);
		long configured = result.get(4);
		budgetedKeys.put(hex, configured > 0);
		if (allowed == 1L) {
			recordDecision("allowed", "none");
			return new BudgetDecision.Allowed(remaining, reset);
		}
		String level = rejected <= 2 ? "KEY" : rejected <= 4 ? "TEAM" : "ORG";
		String window = rejected % 2 == 1 ? "MINUTE" : "MONTH";
		long retryAfter = rejected % 2 == 1 ? Math.max(1L, reset) : secondsToMonthEnd();
		recordDecision("denied", level);
		return new BudgetDecision.Denied(level, window, retryAfter);
	}

	/**
	 * Drops one key from the presence set (key-level CRUD). Next check re-evaluates authoritatively.
	 */
	public void invalidate(String keyHex) {
		budgetedKeys.invalidate(keyHex);
	}

	/**
	 * Drops the whole presence set (team/org/global CRUD, startup backfill refresh). Correct throughout: unknown keys
	 * call the script until re-cached.
	 */
	public void invalidateAll() {
		budgetedKeys.invalidateAll();
	}

	/**
	 * Marks one key budgeted without a round trip (startup backfill, CRUD write-through).
	 */
	public void markBudgeted(String keyHex) {
		budgetedKeys.put(keyHex, Boolean.TRUE);
	}

	private long estimateCostMicros(ProviderType type, String model, int estimatedTokens) {
		try {
			long raw = Math.max(0L, costCalculator.calculate(type, model, estimatedTokens, 0));
			return Math.min(MAX_EXACT_LUA_INTEGER, raw);
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget price estimate unavailable", ex);
		}
	}

	/**
	 * Hold cost {@code H = prompt + maxTokens × output}, priced at each side's own rate. Fail-closed on pricing
	 * failure, like the admission estimate.
	 */
	long holdCostMicros(ProviderType type, String model, int promptTokens, int maxTokens) {
		try {
			long prompt = Math.max(0L, costCalculator.calculate(type, model, Math.max(0, promptTokens), 0));
			long output = Math.max(0L, costCalculator.calculate(type, model, 0, Math.max(0, maxTokens)));
			return Math.min(MAX_EXACT_LUA_INTEGER, Math.addExact(prompt, output));
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget hold estimate unavailable", ex);
		}
	}

	/**
	 * Creates the hold record for an admitted request. Must follow a successful {@link #authorizeHold}; denied
	 * requests never reach here.
	 *
	 * @param holdId       stable id for the request (deterministic per idempotency key, so retries share it)
	 * @param subjectScope subject ref encoded as {@code LEVEL:subject}
	 * @param holdMicros   hold cost H from the authorization
	 * @param origMonth    admission month {@code YYYY-MM}
	 * @param createdEpochSec engine clock seconds (liveness bound only, never money)
	 * @param ttlSeconds   hold-record TTL
	 * @return {@code true} when this call created the record, {@code false} on duplicate hold id
	 * @throws RateLimitUnavailableException when Redis is unreachable
	 */
	public boolean createHold(String holdId, String subjectScope, long holdMicros, String origMonth,
	                          long createdEpochSec, long ttlSeconds) {
		List<String> keys = List.of(holdKey(holdId), holdExpiryKey());
		try {
			List<Long> result = assertTwo(redisTemplate.execute(
					holdScript, keys,
					Long.toString(Math.min(MAX_EXACT_LUA_INTEGER, Math.max(0L, holdMicros))),
					subjectScope, origMonth,
					Long.toString(ttlSeconds), Long.toString(createdEpochSec)));
			return result.get(0) == 1L;
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget hold unavailable", ex);
		}
	}

	/**
	 * Trues a hold up to actual spend. Exactly-once via the settled-flag claim inside the script: retries are
	 * idempotent no-ops, never double moves.
	 *
	 * @param holdId       hold id from creation
	 * @param level        subject level (KEY, TEAM, ORG) for the gap marker
	 * @param subject      subject id for the gap marker
	 * @param ownerId      authenticated tenant, possibly {@code null} (skips the TEAM keys)
	 * @param keyHex       KEY-level subject (key sha256 hex)
	 * @param origMonth    admission month from the authorization (rollover detection)
	 * @param currMonth    settlement month from a single settlement-time clock read
	 * @param actualMicros measured actual spend A; -1 selects the expire-only path (sweeper)
	 * @param abortDueEpochSec re-arm time for aborts (0 = none)
	 * @return parsed settle outcome; {@code gapSet} tells the caller to persist a gap row
	 * @throws RateLimitUnavailableException when Redis is unreachable
	 */
	public SettleOutcome settle(String holdId, String level, String subject, @Nullable String ownerId,
	                            String keyHex, String origMonth, String currMonth,
	                            long actualMicros, long abortDueEpochSec) {
		String team = ownerId == null || ownerId.isBlank() ? "" : ownerId;
		List<String> keys = List.of(
				settledKey(holdId),
				gapKey(level, subject, currMonth),
				holdKey(holdId),
				monthKey("KEY", keyHex, origMonth),
				team.isEmpty() ? "" : monthKey("TEAM", team, origMonth),
				monthKey("ORG", GLOBAL_ORG, origMonth),
				monthKey("KEY", keyHex, currMonth),
				team.isEmpty() ? "" : monthKey("TEAM", team, currMonth),
				monthKey("ORG", GLOBAL_ORG, currMonth),
				holdExpiryKey(),
				cfgKey("KEY", keyHex)
		);
		try {
			List<Long> result = assertFive(redisTemplate.execute(
					settleScript, keys,
					Long.toString(actualMicros), Long.toString(abortDueEpochSec), currMonth));
			return new SettleOutcome(result.get(0) == 1L, result.get(1).intValue(),
					result.get(2), result.get(3), result.get(4) == 1L);
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget settle unavailable", ex);
		}
	}

	/**
	 * Hold ids whose expiry score has passed (crashed or aborted-grace-lapsed), oldest first, bounded.
	 *
	 * @param limit    max ids to return
	 * @param nowEpochSec engine clock seconds
	 * @return hold hash keys due for expiry
	 */
	public Set<String> dueHoldKeys(int limit, long nowEpochSec) {
		try {
			Set<String> due = redisTemplate.opsForZSet()
			                               .rangeByScore(holdExpiryKey(), 0, nowEpochSec, 0, limit);
			return due == null ? Set.of() : due;
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget hold scan unavailable", ex);
		}
	}

	/**
	 * Re-arms a hold in the expiry index (used when gap-row persistence fails, so the next tick retries).
	 *
	 * @param holdHashKey hold hash key as returned by {@link #dueHoldKeys}
	 * @param scoreEpochSec new expiry score
	 */
	public void rearmHold(String holdHashKey, long scoreEpochSec) {
		try {
			redisTemplate.opsForZSet().add(holdExpiryKey(), holdHashKey, scoreEpochSec);
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget hold re-arm unavailable", ex);
		}
	}

	/**
	 * Reads raw hold-hash fields for gap-row attribution.
	 *
	 * @param holdHashKey hold hash key as returned by {@link #dueHoldKeys}
	 * @return field map, empty when the record is gone
	 */
	public Map<Object, Object> readHold(String holdHashKey) {
		try {
			Map<Object, Object> entries = redisTemplate.opsForHash().entries(holdHashKey);
			return entries == null ? Map.of() : entries;
		} catch (RuntimeException ex) {
			throw new RateLimitUnavailableException("Budget hold read unavailable", ex);
		}
	}

	private static List<Long> assertTwo(@Nullable List<?> result) {
		if (result == null || result.size() != 2) {
			throw new RateLimitUnavailableException("Budget hold script returned an unexpected shape");
		}
		List<Long> checked = new ArrayList<>(2);
		for (Object value : result) {
			if (value instanceof Number number) {
				checked.add(number.longValue());
			} else if (value instanceof String text) {
				try {
					checked.add(Long.parseLong(text.trim()));
				} catch (NumberFormatException malformed) {
					throw new RateLimitUnavailableException("Budget hold script returned a non-numeric value");
				}
			} else {
				throw new RateLimitUnavailableException("Budget hold script returned an unexpected value");
			}
		}
		return checked;
	}

	private void recordDecision(String decision, String level) {
		try {
			Counter.builder("aegis.budget.evaluations")
			       .tag("decision", decision)
			       .tag("level", level)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}
}
