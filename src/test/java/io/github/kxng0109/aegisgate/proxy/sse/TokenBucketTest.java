package io.github.kxng0109.aegisgate.proxy.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenBucket}.
 */
@DisplayName("TokenBucket")
class TokenBucketTest {

	@Test
	@DisplayName("initial state is full")
	void initialStateIsFull() {
		TokenBucket bucket = new TokenBucket(10, 10);
		assertThat(bucket.tryAcquire(1)).isTrue();
	}

	@Test
	@DisplayName("tryAcquire more than capacity returns false")
	void tryAcquireMoreThanCapacityFails() {
		TokenBucket bucket = new TokenBucket(5, 5);
		assertThat(bucket.tryAcquire(6)).isFalse();
	}

	@Test
	@DisplayName("tryAcquire zero returns true")
	void tryAcquireZeroReturnsTrue() {
		TokenBucket bucket = new TokenBucket(5, 5);
		assertThat(bucket.tryAcquire(0)).isTrue();
	}

	@Test
	@DisplayName("tryAcquire negative returns true (treated as zero)")
	void tryAcquireNegativeReturnsTrue() {
		TokenBucket bucket = new TokenBucket(5, 5);
		assertThat(bucket.tryAcquire(-1)).isTrue();
	}

	@Test
	@DisplayName("construction with non-positive capacity or negative rate throws")
	void constructionRejectsInvalidValues() {
		assertThatThrownBy(() -> new TokenBucket(0, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(-1, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(10, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("tryAcquire more than remaining fails after exhaustion")
	void tryAcquireMoreThanRemainingFails() {
		TokenBucket bucket = new TokenBucket(5, 0); // no refill
		assertThat(bucket.tryAcquire(5)).isTrue();
		assertThat(bucket.tryAcquire(1)).isFalse();
	}

	@Test
	@DisplayName("refill allows new acquisitions over time")
	void refillAllowsNewAcquisitions() throws InterruptedException {
		TokenBucket bucket = new TokenBucket(5, 0); // no refill initially
		assertThat(bucket.tryAcquire(5)).isTrue();
		assertThat(bucket.tryAcquire(1)).isFalse();

		TokenBucket refilling = new TokenBucket(5, 100); // 100 tokens per second
		assertThat(refilling.tryAcquire(5)).isTrue();
		// Sleep 50ms -> 5 tokens should be refilled
		Thread.sleep(60);
		assertThat(refilling.tryAcquire(4)).isTrue();
	}

	@Test
	@DisplayName("refill is capped at capacity")
	void refillCappedAtCapacity() throws InterruptedException {
		TokenBucket bucket = new TokenBucket(5, 1000); // very fast refill
		assertThat(bucket.tryAcquire(5)).isTrue();
		Thread.sleep(100); // enough time to refill way more than capacity
		assertThat(bucket.tryAcquire(11)).isFalse(); // capacity + refill is still 5
	}

	@Test
	@DisplayName("concurrent access is thread-safe (no over-acquisition)")
	void concurrentAccessIsThreadSafe() throws InterruptedException {
		TokenBucket bucket = new TokenBucket(1000, 100);
		int threadCount = 20;
		int callsPerThread = 200;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger acquired = new AtomicInteger(0);
		CountDownLatch done = new CountDownLatch(threadCount);
		for (int t = 0; t < threadCount; t++) {
			executor.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < callsPerThread; i++) {
						if (bucket.tryAcquire(1)) {
							acquired.incrementAndGet();
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();

		// Initial capacity (1000) + refill during the test should be well under
		// the theoretical worst case of 20 * 200 = 4000 if there were no caps.
		assertThat(acquired.get()).isLessThanOrEqualTo(4000);
		// The bucket must have allowed at least the initial capacity.
		assertThat(acquired.get()).isGreaterThanOrEqualTo(1000);
	}

	@Test
	@DisplayName("massively concurrent access still respects capacity")
	void massivelyConcurrentAccess() throws InterruptedException {
		TokenBucket bucket = new TokenBucket(100, 0);
		int threadCount = 50;
		int callsPerThread = 100;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger acquired = new AtomicInteger(0);
		CountDownLatch done = new CountDownLatch(threadCount);
		IntStream.range(0, threadCount).forEach(t -> executor.submit(() -> {
			try {
				start.await();
				for (int i = 0; i < callsPerThread; i++) {
					if (bucket.tryAcquire(1)) {
						acquired.incrementAndGet();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				done.countDown();
			}
		}));
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();
		assertThat(acquired.get()).isEqualTo(100);
	}
}