package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AdaptiveSseFlushStrategy}: the dual-trigger thresholds, the shared timer scan, the flush rate
 * limiter, the buffer cap, backpressure detection, the write watchdog, configuration hot reload, and the connection
 * ceiling. Time-dependent behavior is driven with a frozen {@link MutableNanoSource} except where real time is the
 * point of the test (slow flush, watchdog).
 */
@DisplayName("AdaptiveSseFlushStrategy")
class AdaptiveSseFlushStrategyTest {

	private SimpleMeterRegistry registry;

	private MutableNanoSource nano;

	private AdaptiveSseFlushStrategy strategy;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		nano = new MutableNanoSource();
	}

	@AfterEach
	void tearDown() {
		if (strategy != null) {
			strategy.close();
		}
	}

	private AdaptiveSseFlushStrategy newStrategy(SseFlushProperties props) {
		strategy = new AdaptiveSseFlushStrategy(props, registry, nano, 0, 30_000, 100);
		return strategy;
	}

	@Test
	@DisplayName("all metrics are registered with the managed registry")
	void metricsAreRegistered() {
		newStrategy(SseFlushProperties.DEFAULTS);
		registry.get("sse.flush.duration").timer();
		registry.get("sse.flush.backpressure.count").counter();
		registry.get("sse.connection.duration").timer();
		registry.get("sse.connection.active").gauge();
		registry.get("sse.buffer.bytes").gauge();
		registry.get("sse.flush.lag.max_ms").gauge();
		registry.get("sse.backpressure.active_connections").gauge();
	}

	@Test
	@DisplayName("register returns a handle and tracks the active connection")
	void registerReturnsHandleAndTracksConnection() {
		newStrategy(SseFlushProperties.DEFAULTS);
		RecordingServletOutputStream out = new RecordingServletOutputStream();

		SseFlushStrategy.FlushHandle handle = strategy.register(out);

		assertTrue(handle.connectionId() > 0);
		assertSame(out, handle.outputStream());
		assertEquals(1, strategy.registeredConnectionCount());
		assertEquals(1.0, registry.get("sse.connection.active").gauge().value());
	}

	@Test
	@DisplayName("the line threshold triggers a flush once the configured line count is buffered")
	void lineThresholdTriggersFlush() throws Exception {
		newStrategy(new SseFlushProperties(4, 100, 500, 65_536, 1_000, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		for (int i = 0; i < 3; i++) {
			assertFalse(strategy.onWrite(out, 10));
		}
		assertEquals(0, out.flushCount());

		assertFalse(strategy.onWrite(out, 10));
		assertEquals(1, out.flushCount());

		assertFalse(strategy.onWrite(out, 10));
		assertEquals(1, out.flushCount());
	}

	@Test
	@DisplayName("the interval threshold triggers a flush once the configured milliseconds elapsed")
	void intervalThresholdTriggersFlush() throws Exception {
		newStrategy(new SseFlushProperties(16, 100, 500, 65_536, 1_000, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		assertFalse(strategy.onWrite(out, 10));
		nano.advanceMillis(99);
		assertFalse(strategy.onWrite(out, 10));
		assertEquals(0, out.flushCount());

		nano.advanceMillis(2);
		assertFalse(strategy.onWrite(out, 10));
		assertEquals(1, out.flushCount());
	}

	@Test
	@DisplayName("unregister releases the connection, records its duration, and is idempotent")
	void unregisterReleasesAndRecordsDuration() {
		newStrategy(SseFlushProperties.DEFAULTS);
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		SseFlushStrategy.FlushHandle handle = strategy.register(out);

		strategy.unregister(handle);

		assertEquals(0, strategy.registeredConnectionCount());
		assertEquals(0.0, registry.get("sse.connection.active").gauge().value());
		assertEquals(1, registry.get("sse.connection.duration").timer().count());

		strategy.unregister(handle);
		assertEquals(0, strategy.registeredConnectionCount());
	}

	@Test
	@DisplayName("the token bucket caps flush syscalls and refills over time")
	void flushRateLimitSkipsFlushesAndRefills() throws Exception {
		newStrategy(new SseFlushProperties(1, 10, 500, 65_536, 100, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		for (int i = 0; i < 100; i++) {
			assertFalse(strategy.onWrite(out, 10));
		}
		assertEquals(100, out.flushCount());

		assertFalse(strategy.onWrite(out, 10));
		assertEquals(100, out.flushCount());

		nano.advanceMillis(10);
		assertFalse(strategy.onWrite(out, 10));
		assertEquals(101, out.flushCount());
	}

	@Test
	@DisplayName("a producer that outruns its flush budget trips the buffer cap and aborts")
	void bufferOverflowAbortsStream() throws Exception {
		newStrategy(new SseFlushProperties(16, 10, 500, 1_024, 100, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		for (int i = 0; i < 1_600; i++) {
			assertFalse(strategy.onWrite(out, 2));
		}
		assertEquals(100, out.flushCount());

		boolean aborted = false;
		for (int i = 0; i < 600; i++) {
			if (strategy.onWrite(out, 2)) {
				aborted = true;
				break;
			}
		}
		assertTrue(aborted, "the buffer cap must abort a rate-limited flood");
		assertEquals(1.0, registry.get("sse.flush.backpressure.count").counter().count());
	}

	@Test
	@DisplayName("a flush slower than the threshold counts as backpressure and aborts")
	void slowFlushCountsAsBackpressure() throws Exception {
		strategy = new AdaptiveSseFlushStrategy(
				new SseFlushProperties(1, 10, 500, 65_536, 1_000, true),
				registry, System::nanoTime, 0, 30_000, 100
		);
		SlowServletOutputStream out = new SlowServletOutputStream(600);
		strategy.register(out);

		long started = System.nanoTime();
		boolean aborted = strategy.onWrite(out, 10);
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		assertTrue(aborted, "a 600ms flush must trip the 500ms backpressure threshold");
		assertTrue(elapsedMs >= 500);
		assertEquals(1.0, registry.get("sse.flush.backpressure.count").counter().count());
		assertEquals(1, registry.get("sse.flush.duration").timer().count());
	}

	@Test
	@DisplayName("a failed flush aborts the stream")
	void flushFailureAbortsStream() throws Exception {
		newStrategy(new SseFlushProperties(1, 10, 500, 65_536, 1_000, true));
		FailingFlushServletOutputStream out = new FailingFlushServletOutputStream();
		strategy.register(out);

		assertTrue(strategy.onWrite(out, 10));
	}

	@Test
	@DisplayName("disabled mode flushes every line like the legacy relay loop")
	void disabledModeFlushesEveryLine() throws Exception {
		newStrategy(new SseFlushProperties(16, 100, 500, 65_536, 1_000, false));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		for (int i = 0; i < 3; i++) {
			assertFalse(strategy.onWrite(out, 10));
		}
		assertEquals(3, out.flushCount());
	}

	@Test
	@DisplayName("updateConfig hot-swaps the thresholds for live connections")
	void updateConfigChangesThresholds() throws Exception {
		newStrategy(new SseFlushProperties(4, 100, 500, 65_536, 1_000, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);
		for (int i = 0; i < 3; i++) {
			strategy.onWrite(out, 10);
		}

		strategy.updateConfig(new SseFlushProperties(8, 100, 500, 65_536, 1_000, true));

		assertFalse(strategy.onWrite(out, 10));
		assertEquals(0, out.flushCount());
		for (int i = 0; i < 4; i++) {
			strategy.onWrite(out, 10);
		}
		assertEquals(1, out.flushCount());
	}

	@Test
	@DisplayName("the timer tick marks due connections and tracks the flush lag")
	void timerTickMarksDueAndTracksLag() throws Exception {
		newStrategy(new SseFlushProperties(16, 100, 500, 65_536, 1_000, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);
		strategy.onWrite(out, 10);

		nano.advanceMillis(200);
		strategy.onTimerTick();

		assertTrue(strategy.maxFlushLagMs() >= 200);
		assertTrue(registry.get("sse.flush.lag.max_ms").gauge().value() >= 200.0);

		assertFalse(strategy.onWrite(out, 10));
		assertEquals(1, out.flushCount());
	}

	@Test
	@DisplayName("onWrite with an unknown stream is a no-op")
	void onWriteWithUnknownStreamIsNoOp() throws Exception {
		newStrategy(SseFlushProperties.DEFAULTS);
		RecordingServletOutputStream other = new RecordingServletOutputStream();

		assertFalse(strategy.onWrite(other, 10));
		assertEquals(0, other.flushCount());
	}

	@Test
	@DisplayName("the connection ceiling rejects excess streams and releases permits on unregister")
	void connectionCeilingRejectsExcessStreams() {
		strategy = new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry, nano, 0, 30_000, 2);
		SseFlushStrategy.FlushHandle first = strategy.register(new RecordingServletOutputStream());
		strategy.register(new RecordingServletOutputStream());

		assertThrows(
				SseConnectionLimitException.class,
				() -> strategy.register(new RecordingServletOutputStream())
		);

		strategy.unregister(first);
		strategy.register(new RecordingServletOutputStream());
		assertEquals(2, strategy.registeredConnectionCount());
	}

	@Test
	@DisplayName("a flush blocked beyond the watchdog timeout is aborted by the watchdog")
	void watchdogAbortsBlockedFlush() throws Exception {
		strategy = new AdaptiveSseFlushStrategy(
				new SseFlushProperties(1, 10, 500, 65_536, 1_000, true),
				registry, System::nanoTime, 0, 100, 100
		);
		BlockingServletOutputStream out = new BlockingServletOutputStream();
		strategy.register(out);

		AtomicReference<Boolean> aborted = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread writer = Thread.ofVirtual().name("test-writer").start(() -> {
			try {
				aborted.set(strategy.onWrite(out, 10));
			} catch (Throwable ex) {
				failure.set(ex);
			}
		});

		out.awaitFlushStarted();
		writer.join(2_000);
		assertFalse(writer.isAlive(), "the blocked onWrite must return once the watchdog closes the stream");
		assertNull(failure.get(), "onWrite must not throw, it must report the abort as its result");
		assertTrue(Boolean.TRUE.equals(aborted.get()), "the watchdog abort must surface as a backpressure flag");
		assertTrue(out.isClosed(), "the watchdog must close the stuck stream");
	}

	@Test
	@DisplayName("a watchdog close failure is swallowed and the stream still aborts")
	void watchdogCloseFailureIsSwallowed() throws Exception {
		strategy = new AdaptiveSseFlushStrategy(
				new SseFlushProperties(1, 10, 500, 65_536, 1_000, true),
				registry, System::nanoTime, 0, 100, 100
		);
		CloseFailingBlockingServletOutputStream out = new CloseFailingBlockingServletOutputStream();
		strategy.register(out);

		AtomicReference<Boolean> aborted = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread writer = Thread.ofVirtual().name("test-writer").start(() -> {
			try {
				aborted.set(strategy.onWrite(out, 10));
			} catch (Throwable ex) {
				failure.set(ex);
			}
		});

		out.awaitFlushStarted();
		writer.join(2_000);
		assertFalse(writer.isAlive());
		assertNull(failure.get(), "onWrite must not throw, it must report the abort as its result");
		assertTrue(Boolean.TRUE.equals(aborted.get()));
		assertTrue(out.isClosed());
	}

	@Test
	@DisplayName("close terminates all watchdogs and shuts the flush executor down")
	void closeTerminatesWatchdogs() throws Exception {
		newStrategy(SseFlushProperties.DEFAULTS);
		strategy.register(new RecordingServletOutputStream());
		strategy.register(new RecordingServletOutputStream());

		strategy.close();

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (strategy.activeWatchdogCount() > 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(0, strategy.activeWatchdogCount());
	}

	@Test
	@DisplayName("negative line lengths cannot corrupt the buffered byte accounting")
	void negativeLineLengthIsClamped() throws Exception {
		newStrategy(new SseFlushProperties(16, 10, 500, 1_024, 1_000, true));
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);

		assertFalse(strategy.onWrite(out, -5));
		assertEquals(0, strategy.bufferedBytes());
	}

	@Test
	@DisplayName("the automatic registry scan runs on the shared scheduler")
	void automaticTickIsScheduled() throws Exception {
		strategy = new AdaptiveSseFlushStrategy(
				new SseFlushProperties(16, 10, 500, 65_536, 1_000, true),
				registry, nano, 10, 30_000, 100
		);
		RecordingServletOutputStream out = new RecordingServletOutputStream();
		strategy.register(out);
		strategy.onWrite(out, 10);

		nano.advanceMillis(50);

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (strategy.maxFlushLagMs() == 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(strategy.maxFlushLagMs() > 0, "the 10ms scheduler must drive the registry scan");
	}
}