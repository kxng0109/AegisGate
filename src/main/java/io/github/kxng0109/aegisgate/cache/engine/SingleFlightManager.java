package io.github.kxng0109.aegisgate.cache.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-flight request deduplication engine (SingleFlight pattern) preventing cache stampedes and upstream provider
 * throttling under high concurrency (2,000+ requests/sec).
 */
@Slf4j
@Component
public class SingleFlightManager {

	private final ConcurrentMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

	/**
	 * Executes the loader for the given key, deduplicating concurrent executions so only one leader thread computes
	 * while follower threads wait and share the result.
	 *
	 * @param key    deduplication key (e.g. compound hash)
	 * @param loader computation loader to execute if this thread is the leader
	 * @param <T>    result type
	 * @return result of the computation
	 * @throws Exception if computation fails
	 */
	@SuppressWarnings("unchecked")
	public <T> T execute(String key, Callable<T> loader) throws Exception {
		boolean[] isLeader = new boolean[]{false};
		CompletableFuture<T> future = (CompletableFuture<T>) inFlight.computeIfAbsent(
				key, k -> {
					isLeader[0] = true;
					return new CompletableFuture<T>();
				}
		);

		if (isLeader[0]) {
			try {
				T result = loader.call();
				future.complete(result);
			} catch (Throwable t) {
				future.completeExceptionally(t);
			} finally {
				inFlight.remove(key, future);
			}
		}

		try {
			return future.join();
		} catch (Exception ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof Exception e) {
				throw e;
			}
			throw new RuntimeException(cause);
		}
	}
}
