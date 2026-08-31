package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security hardening tests for the flush engine: a malicious upstream flooding tiny lines must be throttled by the
 * per-connection flush budget and eventually aborted by the buffer cap, adversarial line lengths must not corrupt the
 * accounting, and the connection ceiling must shed excess streams.
 */
@DisplayName("SseFlush security")
class SseFlushSecurityTest {

	@Test
	@DisplayName("a tiny-line flood exhausts the flush budget, trips the buffer cap, and aborts")
	void tinyLineFloodIsThrottledAndAborted() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MutableNanoSource nano = new MutableNanoSource();
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						new SseFlushProperties(16, 10, 500, 1_024, 100, true),
						registry, nano, 0, 30_000, 100
				)
		) {
			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);

			// 100 flush budget × 16 lines per flush = 1600 lines of legitimate flushing.
			for (int i = 0; i < 1_600; i++) {
				assertFalse(strategy.onWrite(out, 2));
			}
			assertEquals(100, out.flushCount(), "the token bucket must cap flush syscalls at the configured rate");

			// The frozen clock never refills the bucket; every further line accumulates
			// 2 bytes and the 1KB cap must abort the connection.
			boolean aborted = false;
			for (int i = 0; i < 1_000; i++) {
				if (strategy.onWrite(out, 2)) {
					aborted = true;
					break;
				}
			}
			assertTrue(aborted, "the buffer cap must abort a rate-limited flood");
			assertTrue(registry.get("sse.flush.backpressure.count").counter().count() >= 1.0);
			assertTrue(
					strategy.bufferedBytes() <= 1_024 + 2,
					"the abort must fire as soon as the cap is crossed (at most one line over), got "
							+ strategy.bufferedBytes()
			);
		}
	}

	@Test
	@DisplayName("a negative line length cannot corrupt the buffer accounting")
	void negativeLineLengthCannotCorruptAccounting() throws Exception {
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						new SseFlushProperties(16, 10, 500, 1_024, 1_000, true),
						new SimpleMeterRegistry(), new MutableNanoSource(), 0, 30_000, 100
				)
		) {
			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);

			assertFalse(strategy.onWrite(out, -100));
			assertFalse(strategy.onWrite(out, 0));
			assertEquals(0, strategy.bufferedBytes());

			assertFalse(strategy.onWrite(out, 4));
			assertEquals(4, strategy.bufferedBytes());
		}
	}

	@Test
	@DisplayName("the connection ceiling sheds excess concurrent streams")
	void connectionCeilingShedsExcessStreams() {
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						SseFlushProperties.DEFAULTS, new SimpleMeterRegistry(), new MutableNanoSource(), 0, 30_000, 3)
		) {
			strategy.register(new RecordingServletOutputStream());
			strategy.register(new RecordingServletOutputStream());
			strategy.register(new RecordingServletOutputStream());

			assertThrows(
					SseConnectionLimitException.class,
					() -> strategy.register(new RecordingServletOutputStream())
			);
		}
	}
}