package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.BlockingServletOutputStream;
import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SseFlushHealthIndicator}: the status and the two health details ({@code sse.flush.lag.max_ms},
 * {@code sse.backpressure.active_connections}).
 */
@DisplayName("SseFlushHealthIndicator")
class SseFlushHealthIndicatorTest {

	@Test
	@DisplayName("reports UP with the flush lag and active backpressure details")
	void reportsUpWithLagAndBackpressure() throws Exception {
		MutableNanoSource nano = new MutableNanoSource();
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						SseFlushProperties.DEFAULTS, new SimpleMeterRegistry(), nano, 0, 30_000, 100)
		) {
			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);
			strategy.onWrite(out, 10);
			nano.advanceMillis(250);
			strategy.onTimerTick();

			Health health = new SseFlushHealthIndicator(strategy).health();

			assertNotNull(health);
			assertEquals("UP", health.getStatus().getCode());
			assertTrue(number(health, "sse.flush.lag.max_ms") >= 250);
			assertEquals(0L, number(health, "sse.backpressure.active_connections"));
		}
	}

	@Test
	@DisplayName("counts a blocked flush as active backpressure")
	void countsBlockedFlushAsBackpressure() throws Exception {
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						new SseFlushProperties(1, 10, 500, 65_536, 1_000, true),
						new SimpleMeterRegistry(), System::nanoTime, 0, 30_000, 100
				)
		) {
			BlockingServletOutputStream out = new BlockingServletOutputStream();
			strategy.register(out);

			Thread writer = Thread.ofVirtual().name("health-writer").start(() -> {
				try {
					strategy.onWrite(out, 10);
				} catch (Exception ex) {
					throw new AssertionError(ex);
				}
			});

			out.awaitFlushStarted();

			Health health = new SseFlushHealthIndicator(strategy).health();
			assertNotNull(health);
			assertEquals(1L, number(health, "sse.backpressure.active_connections"));
			assertEquals(1, strategy.backpressureActiveConnections());

			out.releaseFlush();
			writer.join(2_000);
			assertFalse(writer.isAlive());
			assertEquals(
					0L,
					number(new SseFlushHealthIndicator(strategy).health(), "sse.backpressure.active_connections")
			);
		}
	}

	private static long number(Health health, String key) {
		Object value = health.getDetails().get(key);
		assertNotNull(value, "health detail " + key + " must be present");
		return ((Number) value).longValue();
	}
}