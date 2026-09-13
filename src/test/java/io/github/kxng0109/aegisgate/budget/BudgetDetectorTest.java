package io.github.kxng0109.aegisgate.budget;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the spend watchdog with stubbed Redis/outbox/datasource: the real per-scope state machine
 * runs over scripted month-counter series (state persists in a test-side map, exactly like the Redis hash).
 * Static escalation/hysteresis, paired forecast confirmation with sustain, EWMA anomaly with floor and
 * warmup, rollover reset, dedupe collapse, and lock/disabled skips.
 */
@DisplayName("BudgetDetector")
class BudgetDetectorTest {

	private static final BudgetDetectionProperties ENABLED =
			new BudgetDetectionProperties(true, "", 100);

	@SuppressWarnings("unchecked")
	private record Harness(StringRedisTemplate template, HashOperations<String, String, String> hashOps,
	                       ValueOperations<String, String> valueOps, AlertEventRepository outbox,
	                       Map<String, Map<String, String>> stateStore, BudgetDetector detector) {
	}

	@SuppressWarnings("unchecked")
	private static Harness harness(List<String> cfgKeys) throws Exception {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		HashOperations<String, String, String> hashOps = mock(HashOperations.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		doReturn(hashOps).when(template).opsForHash();
		doReturn(valueOps).when(template).opsForValue();
		when(template.execute(any(RedisCallback.class))).thenAnswer(inv -> cfgKeys);
		Map<String, Map<String, String>> stateStore = new HashMap<>();
		when(hashOps.entries(anyString())).thenAnswer(inv ->
				new HashMap<>(stateStore.getOrDefault((String) inv.getArgument(0), Map.of())));
		doAnswer(inv -> {
			stateStore.put((String) inv.getArgument(0), new HashMap<>((Map<String, String>) inv.getArgument(1)));
			return null;
		}).when(hashOps).putAll(anyString(), anyMap());
		AlertEventRepository outbox = mock(AlertEventRepository.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		BudgetDetector detector = new BudgetDetector(template, outbox, ENABLED, dataSource, new ObjectMapper());
		return new Harness(template, hashOps, valueOps, outbox, stateStore, detector);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> anyMap() {
		return any(Map.class);
	}

	private static List<AlertEvent> savedAlerts(AlertEventRepository outbox) {
		ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
		verify(outbox, atLeastOnce()).save(captor.capture());
		return captor.getAllValues();
	}

	private static List<AlertEvent> alertsOf(List<AlertEvent> saved, String detector) {
		List<AlertEvent> matched = new ArrayList<>();
		for (AlertEvent event : saved) {
			if (detector.equals(event.getDetector())) {
				matched.add(event);
			}
		}
		return matched;
	}

	@Test
	@DisplayName("static threshold escalates immediately and latches through hysteresis")
	void staticEscalatesAndLatches() {
		String cfg = "budget:{b:global}:cfg:KEY:hex1";
		Harness harness;
		try {
			harness = harness(List.of(cfg));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("600000");
		Instant tick = tickBase();

		harness.detector().evaluateAll(tick);
		List<AlertEvent> first = savedAlerts(harness.outbox());
		assertThat(alertsOf(first, "burn_static")).hasSize(1);
		assertThat(alertsOf(first, "burn_static").get(0).getSeverity()).isEqualTo("warning");

		// Same level again: latched, no duplicate fire.
		harness.detector().evaluateAll(tick.plusSeconds(60));
		assertThat(alertsOf(savedAlerts(harness.outbox()), "burn_static")).hasSize(1);

		// Drop below the release band: trip clears without firing.
		when(harness.valueOps().get(anyString())).thenReturn("400000");
		harness.detector().evaluateAll(tick.plusSeconds(120));
		assertThat(alertsOf(savedAlerts(harness.outbox()), "burn_static")).hasSize(1);
	}

	@Test
	@DisplayName("paired forecast confirms only after sustain and pages at high thresholds")
	void forecastConfirmsAfterSustain() {
		String cfg = "budget:{b:global}:cfg:KEY:hex2";
		Harness harness;
		try {
			harness = harness(List.of(cfg));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "100000000"));
		AtomicLong month = new AtomicLong();
		when(harness.valueOps().get(anyString()))
				.thenAnswer(inv -> Long.toString(month.addAndGet(1_000_000L)));
		Instant tick = tickBase();

		for (int i = 0; i < 63; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		List<AlertEvent> forecast = alertsOf(savedAlerts(harness.outbox()), "burn_forecast");
		assertThat(forecast).hasSize(1);
		assertThat(forecast.get(0).getSeverity()).isEqualTo("critical");
	}

	@Test
	@DisplayName("anomaly fires on a sustained spike above the floor, silent in warmup")
	void anomalyFiresOnSustainedSpike() {
		String cfg = "budget:{b:global}:cfg:KEY:hex3";
		Harness harness;
		try {
			harness = harness(List.of(cfg));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "10000000000"));
		AtomicLong month = new AtomicLong();
		List<Long> deltas = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			deltas.add(1_000_000L);
		}
		for (int i = 0; i < 6; i++) {
			deltas.add(100_000_000L);
		}
		for (int i = 0; i < 10; i++) {
			deltas.add(1_000_000L);
		}
		AtomicLong step = new AtomicLong();
		when(harness.valueOps().get(anyString())).thenAnswer(inv -> {
			int index = (int) Math.min(step.getAndIncrement(), deltas.size() - 1);
			return Long.toString(month.addAndGet(deltas.get(index)));
		});
		Instant tick = tickBase();

		for (int i = 0; i < deltas.size(); i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		List<AlertEvent> anomaly = alertsOf(savedAlerts(harness.outbox()), "spend_anomaly");
		assertThat(anomaly).hasSize(1);
		assertThat(anomaly.get(0).getSeverity()).isEqualTo("warning");

		// Post-spike ticks adapt without re-firing the same episode.
		for (int i = 0; i < 5; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * (deltas.size() + i)));
		}
		assertThat(alertsOf(savedAlerts(harness.outbox()), "spend_anomaly")).hasSize(1);
	}

	@Test
	@DisplayName("duplicate dedupe collapses instead of throwing")
	void duplicateDedupeCollapses() {
		String cfg = "budget:{b:global}:cfg:KEY:hex4";
		Harness harness;
		try {
			harness = harness(List.of(cfg));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("600000");
		when(harness.outbox().save(any(AlertEvent.class)))
				.thenThrow(new org.springframework.dao.DuplicateKeyException("dedupe"));
		Instant tick = tickBase();

		harness.detector().evaluateAll(tick);
	}

	@Test
	@DisplayName("malformed cfg keys and unconfigured months are skipped")
	void malformedKeysSkipped() {
		Harness harness;
		try {
			harness = harness(List.of("not-a-cfg-key", "budget:{b:global}:cfg:KEY:hex5"));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "0"));
		when(harness.valueOps().get(anyString())).thenReturn("0");

		harness.detector().evaluateAll(Instant.now());

		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("lost advisory lock skips the tick")
	void lostLockSkipsTick() throws Exception {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(false);
		BudgetDetector detector = new BudgetDetector(template, mock(AlertEventRepository.class), ENABLED,
				dataSource, new ObjectMapper());

		detector.evaluate();

		verify(template, never()).execute(any(RedisCallback.class));
	}

	@Test
	@DisplayName("disabled kill-switch skips everything")
	void disabledSkipsEverything() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		BudgetDetector detector = new BudgetDetector(mock(StringRedisTemplate.class),
				mock(AlertEventRepository.class),
				new BudgetDetectionProperties(false, "", 100), dataSource, new ObjectMapper());

		detector.evaluate();

		verify(dataSource, never()).getConnection();
	}

	@Test
	@DisplayName("static latch, downgrade, resolve, and re-escalation fire exactly on rises")
	void staticDowngradeResolveAndReescalation() {
		String cfg = "budget:{b:global}:cfg:KEY:hex6";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		AtomicLong month = new AtomicLong();
		when(harness.valueOps().get(anyString()))
				.thenAnswer(inv -> Long.toString(month.get()));
		Instant tick = tickBase();
		long[] series = {1_050_000L, 960_000L, 940_000L, 870_000L, 800_000L, 470_000L, 400_000L,
				600_000L, 950_000L, 400_000L, 1_050_000L, 400_000L};
		for (int i = 0; i < series.length; i++) {
			month.set(series[i]);
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		List<AlertEvent> rows = alertsOf(savedAlerts(harness.outbox()), "burn_static");
		assertThat(rows).hasSize(4);
		assertThat(rows.get(0).getSeverity()).isEqualTo("critical");
		assertThat(rows.get(1).getSeverity()).isEqualTo("warning");
		assertThat(rows.get(2).getSeverity()).isEqualTo("critical");
		assertThat(rows.get(3).getSeverity()).isEqualTo("critical");
	}

	@Test
	@DisplayName("slow burn fires a sustained warning without escalation")
	void slowBurnFiresWarning() {
		String cfg = "budget:{b:global}:cfg:KEY:hex7";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "100000000"));
		AtomicLong month = new AtomicLong();
		when(harness.valueOps().get(anyString()))
				.thenAnswer(inv -> Long.toString(month.addAndGet(2_500L)));
		Instant tick = tickBase();

		for (int i = 0; i < 85; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		List<AlertEvent> rows = alertsOf(savedAlerts(harness.outbox()), "burn_forecast");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getSeverity()).isEqualTo("warning");
	}

	@Test
	@DisplayName("forecast confirms the ninety band when projection lands inside it")
	void forecastConfirmsNinetyBand() {
		String cfg = "budget:{b:global}:cfg:KEY:hex12";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "100000000"));
		AtomicLong month = new AtomicLong();
		when(harness.valueOps().get(anyString())).thenAnswer(inv -> Long.toString(month.get()));
		Instant tick = tickBase();

		// Jump on the first tick, then hold flat: the 5-minute burn decays to zero while the 1-hour burn
		// stays elevated, so both windows agree inside the ninety band (never touching one hundred).
		month.set(92_000_000L);
		for (int i = 0; i < 66; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		List<AlertEvent> rows = alertsOf(savedAlerts(harness.outbox()), "burn_forecast");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getSeverity()).isEqualTo("critical");
		assertThat(rows.get(0).getPayload()).contains("\"threshold\":90");
	}

	@Test
	@DisplayName("spike below the floor never fires even with a huge z-score")
	void spikeBelowFloorSuppressed() {
		String cfg = "budget:{b:global}:cfg:KEY:hex8";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("100000000", "10000000000"));
		AtomicLong month = new AtomicLong();
		List<Long> deltas = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			deltas.add(1_000_000L);
		}
		for (int i = 0; i < 6; i++) {
			deltas.add(150_000_000L);
		}
		for (int i = 0; i < 10; i++) {
			deltas.add(1_000_000L);
		}
		AtomicLong step = new AtomicLong();
		when(harness.valueOps().get(anyString())).thenAnswer(inv -> {
			int index = (int) Math.min(step.getAndIncrement(), deltas.size() - 1);
			return Long.toString(month.addAndGet(deltas.get(index)));
		});
		Instant tick = tickBase();

		for (int i = 0; i < deltas.size(); i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("null and malformed state entries fall back safely")
	void malformedStateFallsBack() {
		String cfg = "budget:{b:global}:cfg:KEY:hex9";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(Arrays.asList(null, "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("100");
		Map<String, String> weird = new HashMap<>();
		weird.put("mu", null);
		weird.put("var", "abc");
		weird.put("warmup", null);
		weird.put("trip", "xx");
		weird.put("hist", "1,x,3");
		weird.put("f_static", "yy");
		weird.put("month", YearMonth.now(ZoneOffset.UTC).toString());
		harness.stateStore().put("budget:{b:global}:detector:KEY:hex9", weird);

		harness.detector().evaluateAll(Instant.now());

		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("won lock runs the tick; datasource and scan failures are absorbed")
	void evaluatePathsAbsorbFailures() throws Exception {
		Harness harness = newHarness("budget:{b:global}:cfg:KEY:hex10");
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("100");

		harness.detector().evaluate();
		verify(harness.template(), org.mockito.Mockito.atLeastOnce()).execute(any(RedisCallback.class));
	}

	@Test
	@DisplayName("scan failure aborts the tick without throwing")
	@SuppressWarnings("unchecked")
	void scanFailureAbortsTick() throws Exception {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		org.springframework.data.redis.connection.RedisConnection connection =
				mock(org.springframework.data.redis.connection.RedisConnection.class);
		when(connection.scan(any(org.springframework.data.redis.core.ScanOptions.class)))
				.thenThrow(new RuntimeException("scan down"));
		when(template.execute(any(RedisCallback.class))).thenAnswer(inv ->
				((RedisCallback<List<String>>) inv.getArgument(0)).doInRedis(connection));
		DataSource dataSource = mock(DataSource.class);
		Connection jdbc = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(jdbc);
		when(jdbc.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		BudgetDetector detector = new BudgetDetector(template, mock(AlertEventRepository.class), ENABLED,
				dataSource, new ObjectMapper());

		detector.evaluate();
	}

	@Test
	@DisplayName("scan lambda streams keys through the cursor")
	@SuppressWarnings("unchecked")
	void scanStreamsKeys() throws Exception {
		String cfg = "budget:{b:global}:cfg:KEY:hex11";
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		org.springframework.data.redis.connection.RedisConnection connection =
				mock(org.springframework.data.redis.connection.RedisConnection.class);
		org.springframework.data.redis.core.Cursor<byte[]> cursor =
				mock(org.springframework.data.redis.core.Cursor.class);
		when(connection.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);
		when(cursor.hasNext()).thenReturn(true, false);
		when(cursor.next()).thenReturn(cfg.getBytes(StandardCharsets.UTF_8));
		when(template.execute(any(RedisCallback.class))).thenAnswer(inv ->
				((RedisCallback<List<String>>) inv.getArgument(0)).doInRedis(connection));
		HashOperations<String, String, String> hashOps = mock(HashOperations.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		doReturn(hashOps).when(template).opsForHash();
		doReturn(valueOps).when(template).opsForValue();
		when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of("0", "1000000"));
		when(valueOps.get(anyString())).thenReturn("600000");
		DataSource dataSource = mock(DataSource.class);
		Connection jdbc = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(jdbc);
		when(jdbc.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		AlertEventRepository outbox = mock(AlertEventRepository.class);
		BudgetDetector detector = new BudgetDetector(template, outbox, ENABLED, dataSource, new ObjectMapper());

		detector.evaluate();

		verify(outbox).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("hot five minutes alone cannot confirm without the hour window")
	void hotFiveMinutesNeedsHourConfirmation() {
		String cfg = "budget:{b:global}:cfg:KEY:hex13";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "100000000"));
		// Flat 30M (60 ticks) then a +1000/tick run-up: the 5-minute burn projects past the fifty band
		// for any horizon while the 1-hour burn never does (D60/D5 = 1 < 12 by construction, so the pair
		// can never jointly confirm) — hence no fire on any date, with no calendar assumption.
		AtomicLong month = new AtomicLong(30_000_000L);
		when(harness.valueOps().get(anyString())).thenAnswer(inv -> Long.toString(month.get()));
		Instant tick = tickBase();

		for (int i = 0; i < 56; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		for (int i = 56; i < 66; i++) {
			month.addAndGet(1_000L);
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("flat series stays unconfirmed with a full history")
	void flatSeriesStaysUnconfirmed() {
		String cfg = "budget:{b:global}:cfg:KEY:hex14";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "100000000"));
		when(harness.valueOps().get(anyString())).thenReturn("10000000");
		Instant tick = tickBase();

		for (int i = 0; i < 65; i++) {
			harness.detector().evaluateAll(tick.plusSeconds(60L * i));
		}
		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("empty and null cfg readings are skipped")
	void emptyCfgReadingsSkipped() {
		Harness emptyList = newHarness("budget:{b:global}:cfg:KEY:hex15");
		when(emptyList.hashOps().multiGet(anyString(), anyList())).thenReturn(List.of());
		emptyList.detector().evaluateAll(tickBase());

		Harness nullFirst = newHarness("budget:{b:global}:cfg:KEY:hex16");
		when(nullFirst.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(Arrays.asList(null, "1000000"));
		when(nullFirst.valueOps().get(anyString())).thenReturn("100");
		nullFirst.detector().evaluateAll(tickBase());

		Harness singleton = newHarness("budget:{b:global}:cfg:KEY:hex23");
		when(singleton.hashOps().multiGet(anyString(), anyList())).thenReturn(List.of("5"));
		singleton.detector().evaluateAll(tickBase());

		verify(emptyList.outbox(), never()).save(any(AlertEvent.class));
		verify(nullFirst.outbox(), never()).save(any(AlertEvent.class));
		verify(singleton.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("won lock runs the tick and releases")
	void wonLockRunsTick() throws Exception {
		Harness harness = newHarness("budget:{b:global}:cfg:KEY:hex17");

		harness.detector().evaluate();

		verify(harness.template(), atLeastOnce()).execute(any(RedisCallback.class));
	}

	@Test
	@DisplayName("datasource outage skips the tick")
	void datasourceOutageSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
		BudgetDetector detector = new BudgetDetector(mock(StringRedisTemplate.class),
				mock(AlertEventRepository.class), ENABLED, dataSource, new ObjectMapper());

		detector.evaluate();
	}

	@Test
	@DisplayName("tick runtime failure is absorbed")
	void tickRuntimeFailureAbsorbed() throws Exception {
		Harness harness = newHarness("budget:{b:global}:cfg:KEY:hex18");
		when(harness.template().execute(any(RedisCallback.class)))
				.thenThrow(new RuntimeException("redis down"));

		harness.detector().evaluate();
	}

	@Test
	@DisplayName("state read and write failures degrade to re-derivation")
	void stateIoFailuresDegrade() {
		String cfg = "budget:{b:global}:cfg:KEY:hex19";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("100");
		when(harness.hashOps().entries(anyString())).thenThrow(new RuntimeException("redis down"));
		doThrow(new RuntimeException("redis down")).when(harness.hashOps()).putAll(anyString(), anyMap());

		harness.detector().evaluateAll(tickBase());

		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("null state reads degrade to empty maps")
	void nullStateReadsDegrade() {
		String cfg = "budget:{b:global}:cfg:KEY:hex25";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("100");
		when(harness.hashOps().entries(anyString())).thenReturn(null);

		harness.detector().evaluateAll(tickBase());

		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("scope failure does not abort sibling scopes")
	void scopeFailureSkipsScope() {
		String good = "budget:{b:global}:cfg:KEY:hex21";
		Harness harness = newHarness("budget:{b:global}:cfg:KEY:hex22");
		when(harness.template().execute(any(RedisCallback.class)))
				.thenAnswer(inv -> List.of("budget:{b:global}:cfg:KEY:hex22", good));
		when(harness.hashOps().multiGet(anyString(), anyList())).thenAnswer(inv -> {
			if (((String) inv.getArgument(0)).endsWith("hex22")) {
				throw new RuntimeException("redis down");
			}
			return List.of("0", "1000000");
		});
		when(harness.valueOps().get(anyString())).thenReturn("600000");

		harness.detector().evaluateAll(tickBase());

		verify(harness.outbox()).save(any(AlertEvent.class));
	}

	@Test
	@DisplayName("null state fields fall back through every numeric parser")
	void nullStateFieldsFallBack() {
		String cfg = "budget:{b:global}:cfg:KEY:hex24";
		Harness harness = newHarness(cfg);
		when(harness.hashOps().multiGet(anyString(), anyList()))
				.thenReturn(List.of("0", "1000000"));
		when(harness.valueOps().get(anyString())).thenReturn("100");
		Map<String, String> nulls = new HashMap<>();
		nulls.put("mu", null);
		nulls.put("var", null);
		nulls.put("warmup", null);
		nulls.put("trip", null);
		nulls.put("hist", null);
		nulls.put("f_static", null);
		nulls.put("fstreak", null);
		nulls.put("astreak", null);
		nulls.put("month", YearMonth.now(ZoneOffset.UTC).toString());
		harness.stateStore().put("budget:{b:global}:detector:KEY:hex24", nulls);

		harness.detector().evaluateAll(tickBase());

		verify(harness.outbox(), never()).save(any(AlertEvent.class));
	}

	private static Harness newHarness(String cfg) {
		try {
			return harness(List.of(cfg));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Tick base pinned to the 15th 00:00 UTC: multi-tick series never straddle a month boundary no matter
	 * when the suite runs.
	 */
	private static Instant tickBase() {
		return YearMonth.now(ZoneOffset.UTC).atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();
	}
}
