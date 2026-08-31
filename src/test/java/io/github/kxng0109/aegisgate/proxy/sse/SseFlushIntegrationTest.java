package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.SlowServletOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the flush engine in production configuration: the internal shared 10ms timer driving the
 * interval trigger against a real clock, a slow downstream client tripping the backpressure abort, and the automatic
 * registry scan surviving connection churn.
 */
@DisplayName("SseFlush integration")
class SseFlushIntegrationTest {

	@Test
	@DisplayName("the shared timer detects the flush-due connection and the next write ships the batch")
	void sharedTimerFiresAndShipsBufferedLines() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try (
				AdaptiveSseFlushStrategy strategy =
						new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry)
		) {
			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);

			// Five lines stay under the line threshold; only the timer can make them due.
			for (int i = 0; i < 5; i++) {
				strategy.onWrite(out, 10);
			}
			assertEquals(0, out.flushCount());

			Thread.sleep(300);
			assertTrue(
					strategy.maxFlushLagMs() >= 100,
					"the shared timer must have scanned and marked the connection due"
			);

			strategy.onWrite(out, 10);
			assertEquals(1, out.flushCount());
		}
	}

	@Test
	@DisplayName("a slow downstream client trips the backpressure abort through the real flush path")
	void slowClientTripsBackpressure() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try (
				AdaptiveSseFlushStrategy strategy =
						new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry)
		) {
			SlowServletOutputStream out = new SlowServletOutputStream(600);
			strategy.register(out);

			boolean aborted = false;
			for (int i = 0; i < 100; i++) {
				if (strategy.onWrite(out, 10)) {
					aborted = true;
					break;
				}
			}
			assertTrue(aborted, "the slow flush must abort the stream within a few lines");
			assertTrue(registry.get("sse.flush.backpressure.count").counter().count() >= 1.0);
			assertTrue(registry.get("sse.flush.duration").timer().count() >= 1.0);
		}
	}

	@Test
	@DisplayName("the automatic registry scan keeps running without errors across many connections")
	void registryScanSurvivesChurn() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try (
				AdaptiveSseFlushStrategy strategy =
						new AdaptiveSseFlushStrategy(SseFlushProperties.DEFAULTS, registry)
		) {
			RecordingServletOutputStream[] outs = new RecordingServletOutputStream[50];
			SseFlushStrategy.FlushHandle[] handles = new SseFlushStrategy.FlushHandle[50];
			for (int i = 0; i < outs.length; i++) {
				outs[i] = new RecordingServletOutputStream();
				handles[i] = strategy.register(outs[i]);
				strategy.onWrite(outs[i], 10);
			}

			// Let several real 10ms ticks scan the registry while connections churn.
			Thread.sleep(120);
			for (int i = 0; i < outs.length; i++) {
				strategy.unregister(handles[i]);
			}

			assertEquals(0, strategy.registeredConnectionCount());
			assertEquals(0.0, registry.get("sse.connection.active").gauge().value());
			assertEquals(50, registry.get("sse.connection.duration").timer().count());
			Thread.sleep(120);
			assertEquals(0, strategy.registeredConnectionCount());
		}
	}
}