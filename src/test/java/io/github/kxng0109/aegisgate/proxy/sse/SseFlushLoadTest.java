package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load test for the flush engine: one thousand concurrent streams must register, stream, and unregister without leaking
 * registry entries, watchdog virtual threads, or connection permits. The flush engine keeps exactly one shared timer
 * task regardless of connection count, so the structural invariants below are what bound CPU at 1000+ requests per
 * minute; a hard wall-clock ceiling guards against pathological behavior in CI.
 */
@DisplayName("SseFlush load")
class SseFlushLoadTest {

	private static final int STREAMS = 1_000;

	@Test
	@Timeout(30)
	@DisplayName("1000 concurrent streams leave no registry, watchdog, or permit leaks")
	void oneThousandConcurrentStreamsLeaveNoLeaks() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		long started = System.nanoTime();
		try (
				AdaptiveSseFlushStrategy strategy =
						new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry)
		) {
			List<RecordingServletOutputStream> outs = new ArrayList<>(STREAMS);
			List<SseFlushStrategy.FlushHandle> handles = new ArrayList<>(STREAMS);
			for (int i = 0; i < STREAMS; i++) {
				RecordingServletOutputStream out = new RecordingServletOutputStream();
				outs.add(out);
				handles.add(strategy.register(out));
			}
			assertEquals(STREAMS, strategy.registeredConnectionCount());
			assertEquals(STREAMS, registry.get("sse.connection.active").gauge().value());

			// Stream a few lines below the flush thresholds on every connection while the
			// shared 10ms timer scans the full registry.
			for (int i = 0; i < STREAMS; i++) {
				for (int j = 0; j < 8; j++) {
					strategy.onWrite(outs.get(i), 20);
				}
			}
			// Give the 10ms timer enough time to scan the 1000-connection registry at least once.
			// The timer runs every 10ms, so 500ms gives it ~50 ticks to complete a full scan.
			Thread.sleep(500);
			assertTrue(strategy.maxFlushLagMs() > 0, "the shared timer must have scanned the 1000-connection registry");

			for (SseFlushStrategy.FlushHandle handle : handles) {
				strategy.unregister(handle);
			}

			assertEquals(0, strategy.registeredConnectionCount(), "every connection must be unregistered");
			assertEquals(0.0, registry.get("sse.connection.active").gauge().value());
			assertEquals(0.0, registry.get("sse.buffer.bytes").gauge().value());
			assertEquals(STREAMS, registry.get("sse.connection.duration").timer().count());

			// All watchdog virtual threads must exit after unregister.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (liveWatchdogThreads() > 0 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertEquals(0, liveWatchdogThreads(), "every watchdog virtual thread must terminate");

			// The connection gate must be fully released: another thousand can register.
			for (int i = 0; i < STREAMS; i++) {
				strategy.register(new RecordingServletOutputStream());
			}
			assertEquals(STREAMS, strategy.registeredConnectionCount());
		}

		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue(elapsedMs < 25_000, "1000-stream churn completed in " + elapsedMs + "ms");
	}

	private static long liveWatchdogThreads() {
		long count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && thread.getName().startsWith("sse-flush-watchdog-")) {
				count += 1;
			}
		}
		return count;
	}
}