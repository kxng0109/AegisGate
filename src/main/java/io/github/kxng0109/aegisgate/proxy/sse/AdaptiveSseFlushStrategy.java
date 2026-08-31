package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.ServletOutputStream;

import java.io.IOException;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Adaptive dual-trigger SSE flush engine.
 *
 * <p>The streaming virtual thread reports each written line through {@link #onWrite(ServletOutputStream, int)}. A
 * flush is issued when {@code linesSinceFlush >= maxLinesPerFlush} <em>or</em> when
 * {@code now - lastFlushNanos >= maxIntervalMs}, whichever comes first (the dual trigger). This is deliberately not a
 * pure fixed-rate flush: with 1000+ requests per minute a per-connection timer task would create 1000+ scheduled tasks,
 * so one shared {@link ScheduledExecutorService} at 10&nbsp;ms granularity scans the connection registry instead
 * ({@link #onTimerTick()}).</p>
 *
 * <p>The actual {@code flush()} never runs on the scheduler's thread: Tomcat's {@code OutputBuffer} is not
 * thread-safe, so a platform thread flushing would corrupt it. Instead the flush executes through
 * {@link CompletableFuture#delayedExecutor(long, TimeUnit, Executor)} onto a virtual-thread-per-task executor and the
 * streaming virtual thread joins the future. The streaming thread unmounts while waiting, the flush virtual thread
 * unmounts again if the downstream socket blocks (a slow client never pins a carrier), and the join serializes the
 * flush against the next write, so there is zero pinning, zero races, and no extra platform threads. The shared timer
 * only marks due connections and records the flush lag for the health indicator.</p>
 *
 * <p>Hardening: a flush slower than {@code flushBackpressureThresholdMs} or buffered bytes above
 * {@code maxBufferBytes} aborts the stream ({@code onWrite} returns {@code true}) so the caller cancels the upstream
 * HTTP/2 exchange (RST_STREAM); a per-connection token bucket caps flush syscalls at {@code maxFlushesPerSecond}; a
 * dedicated virtual-thread watchdog per connection closes the output stream if a flush stays blocked beyond
 * {@code WATCHDOG_TIMEOUT_MS}; and a {@link Semaphore} of {@value #MAX_CONNECTIONS} permits bounds concurrent
 * streams.</p>
 *
 * <p>The registry maps {@code connectionId -> FlushState} in a {@link ConcurrentHashMap} (read by the shared timer)
 * and {@code ServletOutputStream -> FlushState} in an {@link IdentityHashMap}. The identity map is touched only by the
 * owning streaming virtual thread (register, {@code onWrite}, unregister all happen on that one thread), so it needs no
 * synchronization; the timer never sees it. All timing uses the injected {@link LongSupplier} (real time in production,
 * a controllable clock in tests).</p>
 */
public final class AdaptiveSseFlushStrategy implements SseFlushStrategy, AutoCloseable {

	/**
	 * Granularity of the shared registry scan, milliseconds.
	 */
	static final long TICK_PERIOD_MS = 10;

	/**
	 * Hard ceiling on concurrent SSE streams, enforced with {@link #connectionGate}.
	 */
	static final int MAX_CONNECTIONS = 10_000;

	/**
	 * A flush blocked longer than this is killed by the per-connection watchdog.
	 */
	static final long WATCHDOG_TIMEOUT_MS = 30_000L;

	private static final long NANOS_PER_MILLI = 1_000_000L;

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private final AtomicReference<SseFlushProperties> config;

	private final LongSupplier nanoSource;

	private final MeterRegistry meterRegistry;

	private final ConcurrentHashMap<Long, FlushState> connectionsById = new ConcurrentHashMap<>();

	private final IdentityHashMap<ServletOutputStream, FlushState> connectionsByStream = new IdentityHashMap<>();

	private final AtomicLong connectionIdSequence = new AtomicLong();

	private final Semaphore connectionGate;

	private final int maxConnections;

	private final ExecutorService flushExecutor;

	private final Executor delayedFlushExecutor;

	private final ScheduledExecutorService ticker;

	private final long tickPeriodMs;

	private final long watchdogTimeoutMs;

	private final Timer flushDuration;

	private final Counter backpressureCounter;

	private final Timer connectionDuration;

	private final AtomicLong maxFlushLagMs = new AtomicLong();

	/**
	 * Creates the production strategy with the default tick period, watchdog timeout, and connection ceiling.
	 *
	 * <p>The shared registry-scan scheduler is owned internally (deliberately not a Spring bean: a bare
	 * {@link ScheduledExecutorService} bean would be picked up as the executor behind every {@code @Scheduled} task).
	 * It is a single daemon virtual thread, so the tick overhead is constant no matter how many connections the
	 * registry holds.</p>
	 *
	 * @param properties    initial configuration snapshot
	 * @param meterRegistry registry the SSE metrics are registered with
	 */
	public AdaptiveSseFlushStrategy(SseFlushProperties properties, MeterRegistry meterRegistry) {
		this(properties, meterRegistry, System::nanoTime, TICK_PERIOD_MS, WATCHDOG_TIMEOUT_MS, MAX_CONNECTIONS);
	}

	/**
	 * Creates a strategy with fully controllable knobs (used by tests).
	 *
	 * @param properties        initial configuration snapshot
	 * @param meterRegistry     registry the SSE metrics are registered with
	 * @param nanoSource        monotonic time source; freezing it makes the interval and rate triggers deterministic
	 * @param tickPeriodMs      registry scan period; {@code 0} disables the automatic scan (ticks are driven manually)
	 * @param watchdogTimeoutMs watchdog abort threshold in milliseconds
	 * @param maxConnections    concurrent stream ceiling
	 */
	AdaptiveSseFlushStrategy(
			SseFlushProperties properties,
			MeterRegistry meterRegistry,
			LongSupplier nanoSource,
			long tickPeriodMs,
			long watchdogTimeoutMs,
			int maxConnections
	) {
		this.config = new AtomicReference<>(properties);
		this.meterRegistry = meterRegistry;
		this.nanoSource = nanoSource;
		this.tickPeriodMs = tickPeriodMs;
		this.watchdogTimeoutMs = watchdogTimeoutMs;
		this.connectionGate = new Semaphore(maxConnections);
		this.maxConnections = maxConnections;
		this.flushExecutor = Executors.newVirtualThreadPerTaskExecutor();
		this.delayedFlushExecutor = CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, flushExecutor);
		this.ticker = Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("sse-flush-ticker", 0).factory());
		this.flushDuration = Timer.builder("sse.flush.duration")
		                          .description("Time spent flushing SSE output buffers to downstream clients")
		                          .publishPercentileHistogram()
		                          .register(meterRegistry);
		this.backpressureCounter = Counter.builder("sse.flush.backpressure.count")
		                                  .description(
				                                  "SSE streams aborted because a flush exceeded the backpressure threshold or the buffer cap")
		                                  .register(meterRegistry);
		this.connectionDuration = Timer.builder("sse.connection.duration")
		                               .description("Lifetime of an SSE relay connection")
		                               .register(meterRegistry);
		Gauge.builder("sse.connection.active", connectionsById, ConcurrentHashMap::size)
		     .description("Currently active SSE relay connections")
		     .register(meterRegistry);
		Gauge.builder("sse.buffer.bytes", this, AdaptiveSseFlushStrategy::bufferedBytes)
		     .description("Total bytes buffered and not yet flushed across all SSE connections")
		     .baseUnit("bytes")
		     .register(meterRegistry);
		Gauge.builder("sse.flush.lag.max_ms", maxFlushLagMs, AtomicLong::get)
		     .description("Maximum observed delay between a connection becoming flush-due and the flush being issued")
		     .baseUnit("ms")
		     .register(meterRegistry);
		Gauge.builder(
				     "sse.backpressure.active_connections",
				     this,
				     AdaptiveSseFlushStrategy::backpressureActiveConnections
		     )
		     .description("SSE connections currently blocked in a flush")
		     .register(meterRegistry);
		if (tickPeriodMs > 0) {
			ticker.scheduleAtFixedRate(this::onTimerTick, tickPeriodMs, tickPeriodMs, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public boolean onWrite(ServletOutputStream out, int lineLength) throws IOException {
		SseFlushProperties props = config.get();
		if (!props.enabled()) {
			// Pass-through mode: the caller expects every line shipped immediately.
			out.flush();
			return false;
		}
		FlushState state = connectionsByStream.get(out);
		if (state == null) {
			return false;
		}
		long now = nanoSource.getAsLong();
		state.linesSinceFlush += 1;
		state.bytesBuffered += Math.max(lineLength, 0);
		boolean backpressured = false;
		if (state.flushDue
				|| state.linesSinceFlush >= props.maxLinesPerFlush()
				|| now - state.lastFlushNanos >= props.maxIntervalMs() * NANOS_PER_MILLI) {
			backpressured = doFlush(state, props);
		}
		if (state.bytesBuffered > props.maxBufferBytes()) {
			backpressureCounter.increment();
			backpressured = true;
		}
		return backpressured;
	}

	@Override
	public void onTimerTick() {
		SseFlushProperties props = config.get();
		long now = nanoSource.getAsLong();
		long intervalNanos = props.maxIntervalMs() * NANOS_PER_MILLI;
		long maxLag = maxFlushLagMs.get();
		for (FlushState state : connectionsById.values()) {
			if (!state.terminated && state.linesSinceFlush > 0 && now - state.lastFlushNanos >= intervalNanos) {
				state.flushDue = true;
				long lagMs = (now - state.lastFlushNanos) / NANOS_PER_MILLI;
				if (lagMs > maxLag) {
					maxLag = lagMs;
				}
			}
		}
		maxFlushLagMs.set(maxLag);
	}

	@Override
	public FlushHandle register(ServletOutputStream out) {
		if (!connectionGate.tryAcquire()) {
			throw new SseConnectionLimitException("concurrent SSE stream limit of " + maxConnections + " reached");
		}
		long id = connectionIdSequence.incrementAndGet();
		long now = nanoSource.getAsLong();
		FlushState state = new FlushState(id, out, now, config.get().maxFlushesPerSecond());
		connectionsById.put(id, state);
		connectionsByStream.put(out, state);
		try {
			state.watchdog = Thread.ofVirtual().name("sse-flush-watchdog-" + id).start(() -> watchdogLoop(state));
		} catch (RuntimeException ex) {
			connectionsById.remove(id);
			connectionsByStream.remove(out);
			connectionGate.release();
			throw ex;
		}
		return new Handle(id, out);
	}

	@Override
	public void unregister(FlushHandle handle) {
		FlushState state = connectionsById.remove(handle.connectionId());
		if (state == null) {
			return;
		}
		connectionsByStream.remove(state.out);
		state.terminated = true;
		LockSupport.unpark(state.watchdog);
		connectionGate.release();
		connectionDuration.record(Duration.ofNanos(nanoSource.getAsLong() - state.registeredNanos));
	}

	@Override
	public void updateConfig(SseFlushProperties props) {
		config.set(props);
	}

	@Override
	public void close() {
		for (FlushState state : connectionsById.values()) {
			state.terminated = true;
			LockSupport.unpark(state.watchdog);
		}
		flushExecutor.shutdownNow();
		ticker.shutdownNow();
	}

	/**
	 * @return number of connections currently tracked by the registry
	 */
	int registeredConnectionCount() {
		return connectionsById.size();
	}

	/**
	 * @return number of watchdog virtual threads still alive
	 */
	long activeWatchdogCount() {
		long count = 0;
		for (FlushState state : connectionsById.values()) {
			if (state.watchdog != null && state.watchdog.isAlive()) {
				count += 1;
			}
		}
		return count;
	}

	/**
	 * @return the maximum flush lag observed by the shared timer, milliseconds
	 */
	long maxFlushLagMs() {
		return maxFlushLagMs.get();
	}

	/**
	 * @return number of connections currently blocked inside a flush
	 */
	long backpressureActiveConnections() {
		long count = 0;
		for (FlushState state : connectionsById.values()) {
			if (state.flushInProgress) {
				count += 1;
			}
		}
		return count;
	}

	/**
	 * @return total bytes buffered and not yet flushed across all connections
	 */
	long bufferedBytes() {
		long total = 0;
		for (FlushState state : connectionsById.values()) {
			total += state.bytesBuffered;
		}
		return total;
	}

	private boolean doFlush(FlushState state, SseFlushProperties props) {
		state.flushDue = false;
		long now = nanoSource.getAsLong();
		if (!state.tokens.tryConsume(now, props.maxFlushesPerSecond())) {
			// Rate limited: skip the flush. The buffered bytes keep accumulating and the
			// buffer cap eventually aborts a producer that outruns its flush budget.
			return false;
		}
		state.flushInProgress = true;
		state.flushStartedNanos = now;
		try {
			CompletableFuture.runAsync(() -> flush(state.out), delayedFlushExecutor).join();
		} catch (RuntimeException ex) {
			// The flush failed: the client disconnected or the watchdog closed the stream.
			state.flushInProgress = false;
			state.terminated = true;
			LockSupport.unpark(state.watchdog);
			return true;
		}
		state.flushInProgress = false;
		long finished = nanoSource.getAsLong();
		long durationMs = (finished - state.flushStartedNanos) / NANOS_PER_MILLI;
		flushDuration.record(Duration.ofMillis(durationMs));
		state.lastFlushNanos = finished;
		state.linesSinceFlush = 0;
		state.bytesBuffered = 0;
		if (durationMs >= props.flushBackpressureThresholdMs()) {
			backpressureCounter.increment();
			return true;
		}
		return false;
	}

	private static void flush(ServletOutputStream out) {
		try {
			out.flush();
		} catch (IOException ex) {
			throw new CompletionException(ex);
		}
	}

	private void watchdogLoop(FlushState state) {
		long parkNanos = Math.min(NANOS_PER_SECOND, watchdogTimeoutMs * NANOS_PER_MILLI / 2);
		while (!state.terminated) {
			if (state.flushInProgress
					&& nanoSource.getAsLong() - state.flushStartedNanos >= watchdogTimeoutMs * NANOS_PER_MILLI) {
				try {
					state.out.close();
				} catch (IOException ex) {
					// The stream is already closed; there is nothing left to abort.
				}
				return;
			}
			LockSupport.parkNanos(parkNanos);
		}
	}

	/**
	 * Per-connection mutable flush state.
	 *
	 * <p>The id and stream are final; everything else is volatile because the shared timer reads the registry values
	 * while the owning streaming thread writes them. The {@link TokenBucket} is confined to the owning thread.</p>
	 */
	static final class FlushState {

		final long connectionId;

		final ServletOutputStream out;

		final long registeredNanos;

		final TokenBucket tokens;

		volatile long lastFlushNanos;

		volatile int linesSinceFlush;

		volatile long bytesBuffered;

		volatile boolean flushDue;

		volatile boolean flushInProgress;

		volatile long flushStartedNanos;

		volatile boolean terminated;

		volatile Thread watchdog;

		FlushState(long connectionId, ServletOutputStream out, long nowNanos, double flushRate) {
			this.connectionId = connectionId;
			this.out = out;
			this.registeredNanos = nowNanos;
			this.lastFlushNanos = nowNanos;
			this.tokens = new TokenBucket(nowNanos, flushRate);
		}
	}

	/**
	 * Token bucket for the per-connection flush syscall cap.
	 *
	 * <p>Accessed only by the owning streaming virtual thread. The capacity and refill rate are taken from the current
	 * configuration on every attempt, so a hot reload applies to live connections immediately.</p>
	 */
	static final class TokenBucket {

		private double tokens;

		private long lastRefillNanos;

		TokenBucket(long nowNanos, double initialTokens) {
			this.tokens = initialTokens;
			this.lastRefillNanos = nowNanos;
		}

		boolean tryConsume(long nowNanos, double ratePerSecond) {
			double elapsedSeconds = (nowNanos - lastRefillNanos) / (double) NANOS_PER_SECOND;
			tokens = Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond);
			lastRefillNanos = nowNanos;
			if (tokens >= 1.0) {
				tokens -= 1.0;
				return true;
			}
			return false;
		}
	}

	/**
	 * Immutable per-connection handle handed to callers.
	 *
	 * @param connectionId the assigned connection id
	 * @param outputStream the registered output stream
	 */
	record Handle(long connectionId, ServletOutputStream outputStream) implements FlushHandle {
	}
}