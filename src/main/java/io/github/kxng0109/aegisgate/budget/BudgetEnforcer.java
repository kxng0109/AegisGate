package io.github.kxng0109.aegisgate.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
@Slf4j
@Service
public class BudgetEnforcer {

	/**
	 * Subject id for the single global org scope.
	 */
	public static final String GLOBAL_ORG = "global";

	private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

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
		                            .expireAfterWrite(PRESENCE_TTL)
		                            .build();
	}

	static String cfgKey(String level, String subject) {
		return "budget:cfg:" + level + ":" + subject;
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
		return "budget:" + level + ":" + subject + ":minute:" + epochMinute;
	}

	static String monthKey(String level, String subject, String yearMonth) {
		return "budget:" + level + ":" + subject + ":month:" + yearMonth;
	}

	static long secondsToMonthEnd() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime end = YearMonth.now(ZoneOffset.UTC)
		                             .atEndOfMonth()
		                             .atTime(23, 59, 59)
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
	 * @return allowed with remaining spend, or denied with the binding level, window, and retry horizon
	 * @throws RateLimitUnavailableException when Redis is unreachable (fail-closed for every key class)
	 */
	public BudgetDecision checkBudget(
			SHA256Hash keyHash, @Nullable String ownerId,
			ProviderType type, String model, int estimatedTokens
	) {
		String hex = keyHash.hex();
		Boolean known = budgetedKeys.getIfPresent(hex);
		if (Boolean.FALSE.equals(known)) {
			recordDecision("allowed", "none");
			return new BudgetDecision.Allowed(-1L, 0L);
		}
		long estimatedMicros = estimateCostMicros(type, model, estimatedTokens);
		long epochMinute = System.currentTimeMillis() / 60_000L;
		String month = YearMonth.now(ZoneOffset.UTC).toString();
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
			result = assertFive(redisTemplate.execute(budgetScript, keys, Long.toString(estimatedMicros)));
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
			return Math.max(0L, costCalculator.calculate(type, model, estimatedTokens, 0));
		} catch (RuntimeException ex) {
			log.debug("Budget price estimate unavailable, treating as zero-cost: {}", ex.getMessage());
			return 0L;
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
