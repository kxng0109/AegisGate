package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Distributed, atomic RPM + TPM rate limiter backed by Redis.
 *
 * <p>Each check executes a single Lua script ({@code rate_limit.lua}) that
 * atomically increments the request and token counters for the key in one round-trip, eliminating the race conditions
 * of read-check-write sequences. The script returns the raw counts, the remaining budgets, and the counter TTLs as
 * exactly six integers; this engine maps them onto the {@link RateLimitDecision} contract consumed by the filter
 * layer.</p>
 *
 * <p>Fail-closed: any Redis backend failure ({@link DataAccessException} or
 * {@link PoolException}) or malformed result raises {@link RateLimitUnavailableException} so callers reject the request
 * instead of letting it through unthrottled. The script is executed via Spring Data Redis {@link DefaultRedisScript},
 * which performs the EVALSHA call with a transparent NOSCRIPT fallback to EVAL.</p>
 */
@Service
public class RateLimitEngine {

	/**
	 * Fixed-window length used by the Lua script, in milliseconds.
	 *
	 * <p>Retained as the documented fallback default mirrored in
	 * {@link RateLimitProperties#DEFAULTS}; the engine honors the bound properties.</p>
	 */
	public static final long WINDOW_MILLIS = 60_000L;

	/**
	 * Upper clamp applied to {@code estimatedTokens} before it reaches Redis.
	 */
	public static final int MAX_ESTIMATED_TOKENS = 1_000_000;

	/**
	 * Lower clamp applied to {@code estimatedTokens} before it reaches Redis.
	 */
	public static final int MIN_ESTIMATED_TOKENS = 1;

	private static final String RATELIMIT_KEY_PREFIX = "ratelimit:";
	private static final int RESULT_SIZE = 6;
	private static final long ALLOWED = 1L;
	private static final long REJECTED_RPM = 1L;

	private final StringRedisTemplate redisTemplate;
	private final DefaultRedisScript<List> rateLimitScript;
	private final RateLimitProperties properties;
	private final MeterRegistry meterRegistry;

	/**
	 * Creates the engine with default ceilings.
	 *
	 * @param redisTemplate   Redis template
	 * @param rateLimitScript atomic fixed-window Lua script
	 */
	public RateLimitEngine(StringRedisTemplate redisTemplate, DefaultRedisScript<List> rateLimitScript) {
		this(redisTemplate, rateLimitScript, RateLimitProperties.DEFAULTS, null);
	}

	/**
	 * Creates the engine with explicit ceilings.
	 *
	 * @param redisTemplate   Redis template
	 * @param rateLimitScript atomic fixed-window Lua script
	 * @param properties      window and clamp ceilings
	 * @param meterRegistry   metrics registry (optional; isolated fallback when null)
	 */
	@Autowired
	public RateLimitEngine(
			StringRedisTemplate redisTemplate,
			DefaultRedisScript<List> rateLimitScript,
			RateLimitProperties properties,
			@Nullable MeterRegistry meterRegistry
	) {
		this.redisTemplate = redisTemplate;
		this.rateLimitScript = rateLimitScript;
		this.properties = properties;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Parses one element of the Lua result list.
	 *
	 * <p>The elements arrive as {@link String} values when the connection uses
	 * the {@code StringRedisTemplate} serializer or as {@link Long} values with a native serializer; both are tolerated
	 * by normalizing through {@link String#valueOf(Object)} before parsing.</p>
	 *
	 * @param value raw element from the script result list
	 * @return the numeric value of the element
	 * @throws NumberFormatException if the element is not a parseable integer
	 */
	private static long parseLong(Object value) {
		return Long.parseLong(String.valueOf(value));
	}

	/**
	 * Converts a remaining-budget {@code long} to {@code int}, defensively clamping out-of-range values.
	 *
	 * <p>The script already clamps remaining budgets into {@code [0, limit]}, so
	 * a value outside {@code [0, Integer.MAX_VALUE]} can only appear from corrupted backend data; it is normalized to
	 * the nearest bound.</p>
	 *
	 * @param value remaining-budget value returned by the script
	 * @return the value clamped into {@code [0, Integer.MAX_VALUE]}
	 */
	private static int toInt(long value) {
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return value < 0 ? 0 : (int) value;
	}

	/**
	 * Checks the RPM and TPM budgets for a key, consuming {@code estimatedTokens} from the token budget.
	 *
	 * <p>{@code estimatedTokens} is clamped to
	 * {@link #MIN_ESTIMATED_TOKENS}..{@link #MAX_ESTIMATED_TOKENS} before it reaches Redis, so a hostile or broken
	 * caller cannot under- or over-charge the token counter. The Redis keys are derived from the key hash only; no key
	 * material or hash value is ever included in messages or logs.</p>
	 *
	 * @param keyHash         SHA-256 of the plaintext API key; derives the Redis counter keys
	 * @param key             resolved virtual API key carrying the configured limits
	 * @param estimatedTokens caller estimate of tokens this request consumes; clamped
	 * @return {@link RateLimitDecision.Allowed} with the post-check state, or {@link RateLimitDecision.Rejected} with
	 * the retry-after period
	 * @throws RateLimitUnavailableException if the Redis backend is unreachable or returns a malformed result
	 *                                       (fail-closed)
	 */
	public RateLimitDecision checkRateLimit(SHA256Hash keyHash, VirtualApiKey key, int estimatedTokens) {
		int clampedTokens = Math.clamp(
				estimatedTokens,
				properties.minEstimatedTokens(),
				properties.maxEstimatedTokens()
		);

		// Hash-tagged keys ({hex}) so the RPM/TPM pair shares one Cluster slot; the Lua script
		// touches both keys atomically and would fail with CROSSSLOT otherwise.
		String tag = "{" + keyHash.hex() + "}";
		List<String> keys = List.of(
				RATELIMIT_KEY_PREFIX + tag + ":rpm",
				RATELIMIT_KEY_PREFIX + tag + ":tpm"
		);
		List<String> args = List.of(
				String.valueOf(key.rpmLimit()),
				String.valueOf(clampedTokens),
				String.valueOf(key.tpmLimit()),
				String.valueOf(properties.windowMillis())
		);

		List<?> result;
		try {
			result = redisTemplate.execute(rateLimitScript, keys, args.toArray());
		} catch (DataAccessException | PoolException ex) {
			throw new RateLimitUnavailableException("Redis rate-limit backend unavailable", ex);
		}

		if (result == null || result.size() != RESULT_SIZE) {
			String size = result == null ? "null" : String.valueOf(result.size());
			throw new RateLimitUnavailableException("Unexpected rate-limit result from Redis: " + size);
		}

		long allowed = parseLong(result.getFirst());
		long rpmRemaining = parseLong(result.get(1));
		long rpmResetSeconds = parseLong(result.get(2));
		long tpmRemaining = parseLong(result.get(3));
		long tpmResetSeconds = parseLong(result.get(4));
		long rejected = parseLong(result.get(5));

		if (allowed == ALLOWED) {
			long nowMillis = System.currentTimeMillis();
			Instant now = Instant.ofEpochMilli(nowMillis);
			RateLimitState state = new RateLimitState(
					key.rpmLimit(),
					toInt(rpmRemaining),
					now.plusSeconds(rpmResetSeconds),
					key.tpmLimit(),
					toInt(tpmRemaining),
					now.plusSeconds(tpmResetSeconds)
			);
			recordDecision(true);
			return new RateLimitDecision.Allowed(state);
		}

		RejectionReason reason = rejected == REJECTED_RPM
				? RejectionReason.RPM_EXCEEDED
				: RejectionReason.TPM_EXCEEDED;
		long retryAfterSeconds = rejected == REJECTED_RPM
				? Math.max(1, rpmResetSeconds)
				: Math.max(1, tpmResetSeconds);
		recordDecision(false);
		return new RateLimitDecision.Rejected(reason, retryAfterSeconds);
	}

	private void recordDecision(boolean allowed) {
		try {
			Counter.builder("aegis.ratelimit.evaluations")
			       .tag("decision", allowed ? "allowed" : "denied")
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}
}