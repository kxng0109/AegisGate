package io.github.kxng0109.aegisgate.proxy.sse;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Controllable monotonic time source for the SSE flush tests.
 *
 * <p>Starts at one second so elapsed-time arithmetic never underflows. Advancing it is the only way to make the
 * strategy's interval and rate-limit triggers fire; a frozen clock leaves the elapsed time at zero.</p>
 */
public final class MutableNanoSource implements LongSupplier {

	private static final long NANOS_PER_MILLI = 1_000_000L;

	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	/**
	 * Advances the clock by the given number of milliseconds.
	 *
	 * @param millis how far to advance
	 */
	public void advanceMillis(long millis) {
		now.addAndGet(millis * NANOS_PER_MILLI);
	}

	@Override
	public long getAsLong() {
		return now.get();
	}
}