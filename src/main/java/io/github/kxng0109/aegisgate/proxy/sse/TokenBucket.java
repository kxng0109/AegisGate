package io.github.kxng0109.aegisgate.proxy.sse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe token bucket rate limiter using a lock-free CAS loop.
 *
 * <p>Implements the standard token bucket algorithm as described in
 * <a href="https://en.wikipedia.org/wiki/Token_bucket">Wikipedia</a>. Tokens
 * are added to the bucket at a constant rate, and a request to acquire N tokens succeeds only if N tokens are
 * available. The bucket is capped at a maximum capacity.</p>
 *
 * <p>Thread safety is achieved with an atomic state (immutable record)
 * and a compare-and-set retry loop, matching the pattern used by the JDK's own {@code LimitingSubscriber}. This
 * guarantees that no two concurrent threads can race on token state or the last-refill timestamp.</p>
 */
final class TokenBucket {

	private final double capacity;
	private final double refillPerSecond;
	private final AtomicReference<State> state;

	TokenBucket(double capacity, double refillPerSecond) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		if (refillPerSecond < 0) {
			throw new IllegalArgumentException("refillPerSecond must not be negative");
		}
		this.capacity = capacity;
		this.refillPerSecond = refillPerSecond;
		this.state = new AtomicReference<>(new State(capacity, System.nanoTime()));
	}

	/**
	 * Attempts to acquire the given number of tokens.
	 *
	 * <p>If sufficient tokens are available (after refilling based on elapsed
	 * time), the tokens are deducted and the method returns {@code true}. Otherwise no tokens are deducted and the
	 * method returns {@code false}.</p>
	 *
	 * @param tokensRequested number of tokens to acquire
	 * @return {@code true} if tokens were acquired, {@code false} if rate limit exceeded
	 */
	boolean tryAcquire(double tokensRequested) {
		if (tokensRequested <= 0) {
			return true;
		}
		while (true) {
			State current = state.get();
			long now = System.nanoTime();
			double elapsed = (now - current.lastRefillNanos) / 1_000_000_000.0;
			double refilled = Math.min(capacity, current.tokens + elapsed * refillPerSecond);
			if (refilled < tokensRequested) {
				return false;
			}
			State next = new State(refilled - tokensRequested, now);
			if (state.compareAndSet(current, next)) {
				return true;
			}
		}
	}

	/**
	 * Immutable state for the token bucket, swapped atomically via {@link AtomicReference#compareAndSet}.
	 *
	 * @param tokens          current token count (always &ge; 0, &le; capacity)
	 * @param lastRefillNanos monotonic timestamp of the last refill (System.nanoTime)
	 */
	private record State(double tokens, long lastRefillNanos) {
	}
}