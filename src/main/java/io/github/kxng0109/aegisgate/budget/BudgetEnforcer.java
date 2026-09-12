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
	static final long MAX_EXACT_LUA_INTEGER = 9_007_199_254_099_001L;

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

	private final CostCalculator costCalculator;

	private final MeterRegistry meterRegistry;

	private final Cache<String, Boolean> budgetedKeys;

	/**
	 * @param redisTemplate  Redis access for the atomic script
	 * @param budgetScript   the {@code budget_limit.lua} script bean
	 * @param costCalculator prompt-side price estimates (catalog-backed, no I/O beyond Caffeine)
	 * @param meterRegistry  metrics registry (optional; isolated fallback when null)
	 */
	@Autowired
	public BudgetEnforcer(
			StringRedisTemplate redisTemplate,
			@Qualifier("budgetLimitScript") DefaultRedisScript<List> budgetScript,
			CostCalculator costCalculator,
			@Nullable MeterRegistry meterRegistry
	) {
		this.redisTemplate = redisTemplate;
		this.budgetScript = budgetScript;
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
		long epochMinute = now.toEpochMilli() / 60_000L;
		String month = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
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
					budgetScript, keys, Long.toString(estimatedMicros),
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
