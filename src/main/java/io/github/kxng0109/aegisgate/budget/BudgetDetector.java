package io.github.kxng0109.aegisgate.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Background spend watchdog: static budget thresholds, exhaustion forecast, and burn-rate anomaly detection.
 * Never on the request hot path — it reads Redis counters once a minute and writes alert decisions to the
 * {@code alert_events} outbox for the dispatcher. Single-flight across pods via a session-level advisory lock;
 * a lost lock skips the tick (next minute retries), so overlapping evaluations cannot double-alert (and the
 * dedupe SHA would collapse them anyway).
 *
 * <p>Numerics: all money stays {@code long} micros; EWMA mean/variance live in {@code double} as dimensionless
 * statistics (β = α = 0.2, verified pairing). Deltas derive from month-counter differences each tick, never
 * from a drifted double proxy. The z-score is one-sided (overspend only) with a σ floor of 1 micros/min and an
 * operational floor of {@code max($50/min, 2× scope minute cap)}.</p>
 */
@Component
public class BudgetDetector {

	static final String LOCK_NAME = "aegis-budget-detector";

	static final double ALPHA = 0.2;

	static final int WARMUP_TICKS = 15;

	static final int SUSTAINED_TICKS = 3;

	static final double FLOOR_DOLLARS_PER_MIN = 50.0;

	static final long MICROS_PER_DOLLAR = 1_000_000L;

	private static final Logger log = LoggerFactory.getLogger(BudgetDetector.class);

	private final StringRedisTemplate redisTemplate;

	private final AlertEventRepository outbox;

	private final BudgetDetectionProperties properties;

	private final DataSource dataSource;

	private final ObjectMapper objectMapper;

	public BudgetDetector(StringRedisTemplate redisTemplate, AlertEventRepository outbox,
	                      BudgetDetectionProperties properties, DataSource dataSource,
	                      ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.outbox = outbox;
		this.properties = properties;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
	}

	@Scheduled(fixedDelayString = "${gateway.budget.detection.interval:60s}")
	public void evaluate() {
		if (!properties.enabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			if (!AdvisoryLock.tryLock(connection, LOCK_NAME)) {
				return;
			}
			try {
				evaluateAll(Instant.now());
			} finally {
				AdvisoryLock.unlock(connection, LOCK_NAME);
			}
		} catch (SQLException ex) {
			log.warn("Detector tick skipped (datasource unavailable)");
		} catch (RuntimeException ex) {
			log.warn("Detector tick failed; next minute retries");
		}
	}

	void evaluateAll(Instant now) {
		String month = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		List<String> cfgKeys = scanCfgKeys();
		for (String cfgKey : cfgKeys) {
			try {
				evaluateScope(cfgKey, month, now);
			} catch (RuntimeException ex) {
				log.warn("Detector scope evaluation failed for {}; continuing", cfgKey);
			}
		}
	}

	private List<String> scanCfgKeys() {
		return redisTemplate.execute((RedisCallback<List<String>>) connection -> {
			List<String> keys = new ArrayList<>();
			try (Cursor<byte[]> cursor = connection.scan(
					ScanOptions.scanOptions().match("budget:{b:global}:cfg:*").count(100).build())) {
				while (cursor.hasNext()) {
					keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
				}
			} catch (Exception ex) {
				throw new RuntimeException("cfg scan failed", ex);
			}
			return keys;
		});
	}

	private void evaluateScope(String cfgKey, String month, Instant now) {
		String[] parts = cfgKey.split(":", 5);
		if (parts.length != 5) {
			return;
		}
		String level = parts[3];
		String subject = parts[4];
		List<?> cfg = redisTemplate.opsForHash().multiGet(cfgKey,
				List.of("minute_micros", "month_micros"));
		long minuteCap = parseLong(cfg.size() > 0 && cfg.get(0) != null ? cfg.get(0).toString() : null);
		long monthCap = parseLong(cfg.size() > 1 && cfg.get(1) != null ? cfg.get(1).toString() : null);
		if (monthCap <= 0) {
			return;
		}
		String monthKey = BudgetEnforcer.monthKey(level, subject, month);
		long monthNow = parseLong(redisTemplate.opsForValue().get(monthKey));
		String stateKey = "budget:{b:global}:detector:" + level + ":" + subject;
		Map<String, String> state = readState(stateKey);

		if (!month.equals(state.getOrDefault("month", ""))) {
			// Calendar rollover: counters restarted; latched trips resolve (Alertmanager auto-resolves
			// unsent alerts past resolve_timeout). EWMA state carries over (the spend series continues).
			state = new HashMap<>(state);
			state.put("month", month);
			state.put("trip", "0");
			state.put("astreak", "0");
			state.put("fstreak", "0");
			state.put("firstBreach", "");
		}

		evaluateStatic(level, subject, monthCap, monthNow, month, now, state);
		evaluateForecast(level, subject, monthCap, monthNow, month, now, state);
		evaluateAnomaly(level, subject, minuteCap, monthNow, month, now, state);

		if ("0".equals(state.getOrDefault("trip", "0"))
				&& "0".equals(state.getOrDefault("fstreak", "0"))
				&& "0".equals(state.getOrDefault("astreak", "0"))) {
			state.remove("firstBreach");
		}

		writeState(stateKey, state);
	}

	private void evaluateStatic(String level, String subject, long monthCap, long monthNow,
	                            String month, Instant now, Map<String, String> state) {
		double pct = 100.0 * monthNow / monthCap;
		int trip = parseInt(state.getOrDefault("trip", "0"));
		int next = trip;
		if (pct >= 100.0) {
			next = 100;
		} else if (pct >= 90.0) {
			next = trip == 100 && pct >= 95.0 ? 100 : 90;
		} else if (pct >= 50.0) {
			// A latched 100 can only arrive here below 90, and a latched 90 only below 85: both fall
			// through to the downgrade below their release bands, so no special-casing is needed.
			next = trip == 90 && pct >= 85.0 ? 90 : 50;
		} else if ((trip == 50 && pct < 45.0) || (trip == 90 && pct < 85.0) || (trip == 100 && pct < 95.0)) {
			next = 0;
		} else if (trip == 50) {
			// Hysteresis latch: 90/100 trips with spend under 50 always resolve through the release
			// bands above, so only the 50 latch can survive down here.
			next = trip;
		}
		state.put("trip", Integer.toString(next));
		int fired = parseInt(state.getOrDefault("f_static", "-1"));
		if (next > trip) {
			raise(level, subject, "burn_static", next, pct, month, now, state,
					next >= 90 ? "critical" : "warning");
			state.put("f_static", Integer.toString(next));
		} else if (next < fired) {
			state.put("f_static", Integer.toString(next));
		}
	}

	private void evaluateForecast(String level, String subject, long monthCap, long monthNow,
	                              String month, Instant now, Map<String, String> state) {
		List<Long> hist = parseHist(state.getOrDefault("hist", ""));
		hist.add(monthNow);
		while (hist.size() > 61) {
			hist.remove(0);
		}
		state.put("hist", joinHist(hist));
		if (hist.size() < 61) {
			state.put("fstreak", "0");
			return;
		}
		long burn5 = Math.max(0L, (hist.get(60) - hist.get(55)) / 300L);
		long burn1h = Math.max(0L, (hist.get(60) - hist.get(0)) / 3600L);
		long horizon = Math.max(1L, BudgetEnforcer.secondsToMonthEnd());
		boolean confirmed = false;
		int threshold = 0;
		for (int candidate : new int[]{100, 90, 50}) {
			long need = monthCap * candidate / 100L;
			boolean b5 = hist.get(60) + burn5 * horizon >= need;
			boolean b1h = hist.get(60) + burn1h * horizon >= need;
			if (b5 && b1h) {
				confirmed = true;
				threshold = candidate;
				break;
			}
		}
		int streak = parseInt(state.getOrDefault("fstreak", "0"));
		if (confirmed) {
			streak++;
		} else {
			streak = 0;
			state.put("f_forecast", "-1");
		}
		state.put("fstreak", Integer.toString(streak));
		int fired = parseInt(state.getOrDefault("f_forecast", "-1"));
		if (confirmed && streak >= SUSTAINED_TICKS && fired != threshold) {
			// Fire on first confirmation and on escalation only: a confirmed downgrade (both windows still
			// breaching a lower band) updates the latch silently, exactly like the static trip machine.
			if (threshold > fired) {
				double pct = 100.0 * hist.get(60) / monthCap;
				raise(level, subject, "burn_forecast", threshold, pct, month, now, state,
						threshold >= 90 ? "critical" : "warning");
			}
			state.put("f_forecast", Integer.toString(threshold));
		}
	}

	private void evaluateAnomaly(String level, String subject, long minuteCap, long monthNow,
	                             String month, Instant now, Map<String, String> state) {
		int warmup = parseInt(state.getOrDefault("warmup", "0"));
		long last = parseLong(state.getOrDefault("last", Long.toString(monthNow)));
		long delta = monthNow - last;
		state.put("last", Long.toString(monthNow));
		double mu = parseDouble(state.getOrDefault("mu", "0"));
		double variance = parseDouble(state.getOrDefault("var", "0"));
		if (warmup < WARMUP_TICKS) {
			mu = warmup == 0 ? delta : (1 - ALPHA) * mu + ALPHA * delta;
			variance = (1 - ALPHA) * variance + ALPHA * (delta - mu) * (delta - mu);
			state.put("mu", Double.toString(mu));
			state.put("var", Double.toString(variance));
			state.put("warmup", Integer.toString(warmup + 1));
			state.put("astreak", "0");
			return;
		}
		double sigma = Math.sqrt(Math.max(variance, 1.0));
		double z = (delta - mu) / sigma;
		mu = (1 - ALPHA) * mu + ALPHA * delta;
		state.put("mu", Double.toString(mu));
		long floor = Math.max((long) (FLOOR_DOLLARS_PER_MIN * MICROS_PER_DOLLAR), 2L * minuteCap);
		int streak = parseInt(state.getOrDefault("astreak", "0"));
		if (z > 3.0 && delta > floor) {
			streak++;
			// Freeze the variance while anomalous: the mean keeps tracking (so a genuine level shift
			// eventually re-arms), but sensitivity must not adapt away mid-episode — otherwise the second
			// tick of a sustained spike can never confirm.
		} else {
			streak = 0;
			variance = (1 - ALPHA) * variance + ALPHA * (delta - mu) * (delta - mu);
			state.put("f_anomaly", "-1");
		}
		state.put("var", Double.toString(variance));
		state.put("astreak", Integer.toString(streak));
		if (streak >= SUSTAINED_TICKS && parseInt(state.getOrDefault("f_anomaly", "-1")) != 0) {
			state.put("f_anomaly", "0");
			raise(level, subject, "spend_anomaly", 0, z, month, now, state, "warning");
		}
	}

	private void raise(String level, String subject, String detector, int threshold, double value,
	                   String month, Instant now, Map<String, String> state, String severity) {
		// Minute-truncated tick time: two pods evaluating the same minute derive the identical canonical
		// string, so cross-pod duplicates collapse on the dedupe constraint instead of double-alerting.
		String firstBreach = state.getOrDefault("firstBreach", "");
		if (firstBreach.isEmpty()) {
			firstBreach = now.truncatedTo(ChronoUnit.MINUTES).toString();
			state.put("firstBreach", firstBreach);
		}
		String pct = new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
		String scope = level + ":" + subject;
		String canonical = scope + "|" + detector + "|" + threshold + "|" + pct + "|" + firstBreach
				+ "|" + month;
		String sha = sha256Hex(canonical);
		String payload = "{\"scope\":\"" + scope + "\",\"detector\":\"" + detector + "\",\"threshold\":"
				+ threshold + ",\"value\":" + pct + ",\"month\":\"" + month + "\"}";
		try {
			outbox.save(new AlertEvent(sha, scope, detector, severity, now, payload, pct, month));
		} catch (DataIntegrityViolationException duplicate) {
			// Another tick or pod already recorded this exact alert: collapse, do not duplicate.
		}
	}

	private Map<String, String> readState(String stateKey) {
		try {
			Map<String, String> entries = redisTemplate.<String, String>opsForHash().entries(stateKey);
			return entries == null ? new HashMap<>() : new HashMap<>(entries);
		} catch (RuntimeException ex) {
			return new HashMap<>();
		}
	}

	private void writeState(String stateKey, Map<String, String> state) {
		try {
			redisTemplate.opsForHash().putAll(stateKey, state);
			redisTemplate.expire(stateKey, Duration.ofDays(2));
		} catch (RuntimeException ex) {
			log.debug("Detector state persist failed; next tick re-derives");
		}
	}

	static String sha256Hex(String canonical) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
			                             .digest(canonical.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	private static long parseLong(String value) {
		if (value == null) {
			return 0L;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException malformed) {
			return 0L;
		}
	}

	private static int parseInt(String value) {
		if (value == null) {
			return 0;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException malformed) {
			return 0;
		}
	}

	private static double parseDouble(String value) {
		if (value == null) {
			return 0.0;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException malformed) {
			return 0.0;
		}
	}

	private static List<Long> parseHist(String csv) {
		List<Long> hist = new ArrayList<>();
		if (csv == null || csv.isBlank()) {
			return hist;
		}
		for (String part : csv.split(",")) {
			try {
				hist.add(Long.parseLong(part.trim()));
			} catch (NumberFormatException malformed) {
				hist.add(0L);
			}
		}
		return hist;
	}

	private static String joinHist(List<Long> hist) {
		StringBuilder joined = new StringBuilder();
		for (int i = 0; i < hist.size(); i++) {
			if (i > 0) {
				joined.append(',');
			}
			joined.append(hist.get(i));
		}
		return joined.toString();
	}
}
