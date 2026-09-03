package io.github.kxng0109.aegisgate.ledger.queue;

import io.github.kxng0109.aegisgate.ledger.SpillwayJournalManager;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.ledger.UsageLedgerEntry;
import io.github.kxng0109.aegisgate.ledger.UsageLedgerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-throughput micro-batching ledger writer consuming the lock-free ring buffer and flushing batches to PostgreSQL
 * with dual triggers (batch size $\ge 5,000$ or interval $\ge 50\text{ms}$).
 *
 * <p>Employs transactional batching, circuit-breaking to the durable spillway journal during database outages,
 * and background journal replay upon database recovery.</p>
 */
@Slf4j
@Component
public class MicroBatchLedgerWriter implements SmartLifecycle {

	private final DisruptorUsageLedgerQueue queue;
	private final UsageLedgerRepository repository;
	private final SpillwayJournalManager spillwayJournal;
	private final MeterRegistry meterRegistry;

	private final int maxBatchSize;
	private final long flushIntervalMs;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private @Nullable ScheduledExecutorService scheduler;

	/**
	 * Creates a new micro-batch ledger writer.
	 *
	 * @param queue           the lock-free ring buffer
	 * @param repository      the JPA ledger repository
	 * @param spillwayJournal fallback journal on database outage
	 * @param meterRegistry   metrics registry
	 * @param maxBatchSize    maximum rows in one bulk flush (default 5,000)
	 * @param flushIntervalMs maximum duration before flushing a partial batch (default 50ms)
	 */
	@Autowired
	public MicroBatchLedgerWriter(
			DisruptorUsageLedgerQueue queue,
			UsageLedgerRepository repository,
			SpillwayJournalManager spillwayJournal,
			@Nullable MeterRegistry meterRegistry,
			@Value("${gateway.ledger.batch.max-size:5000}") int maxBatchSize,
			@Value("${gateway.ledger.batch.interval-ms:50}") long flushIntervalMs
	) {
		this.queue = queue;
		this.repository = repository;
		this.spillwayJournal = spillwayJournal;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
		this.maxBatchSize = Math.max(10, maxBatchSize);
		this.flushIntervalMs = Math.max(10L, flushIntervalMs);
	}

	@Override
	public void start() {
		if (running.compareAndSet(false, true)) {
			scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ledger-microbatch-", 0)
			                                                             .factory());
			scheduler.scheduleWithFixedDelay(this::flushCycle, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
			scheduler.scheduleWithFixedDelay(this::replayCycle, 30000, 60000, TimeUnit.MILLISECONDS);
			log.info(
					"MicroBatchLedgerWriter started with maxBatchSize={}, interval={}ms",
					maxBatchSize,
					flushIntervalMs
			);
		}
	}

	@Override
	public void stop() {
		if (running.compareAndSet(true, false) && scheduler != null) {
			scheduler.shutdown();
			try {
				if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
					scheduler.shutdownNow();
				}
			} catch (InterruptedException ex) {
				scheduler.shutdownNow();
				Thread.currentThread().interrupt();
			}
			// Final drain
			flushCycle();
			log.info("MicroBatchLedgerWriter stopped cleanly");
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Executes one flush cycle, draining up to {@code maxBatchSize} records from the queue and saving to PostgreSQL.
	 *
	 * @return count of flushed records
	 */
	public int flushCycle() {
		List<TokenUsageEvent> batch = new ArrayList<>(maxBatchSize);
		int drained = queue.drainTo(batch, maxBatchSize);
		if (drained == 0) {
			return 0;
		}

		List<UsageLedgerEntry> entries = new ArrayList<>(drained);
		for (TokenUsageEvent event : batch) {
			entries.add(new UsageLedgerEntry(
					event.requestId(),
					event.ownerId(),
					event.provider(),
					event.model(),
					safeInt(event.promptTokens()),
					safeInt(event.completionTokens()),
					safeInt(event.totalTokens()),
					event.costUsdMicros(),
					event.durationMs(),
					event.timestamp(),
					safeInt(event.uncachedPromptTokens()),
					safeInt(event.cacheReadTokens()),
					safeInt(event.cacheWriteTokens()),
					safeInt(event.reasoningTokens()),
					event.effectiveCostMicros(),
					event.billedCostMicros(),
					event.requestHash()
			));
		}

		try {
			repository.saveAll(entries);
			recordBatchMetrics(batch);
			return drained;
		} catch (Exception ex) {
			log.warn(
					"Database bulk insert of {} records failed: {}; spilling to disk journal",
					drained, ex.getMessage()
			);
			spillwayJournal.appendBatch(batch, "DB batch write failed: " + ex.getMessage());
			return 0;
		}
	}

	void replayCycle() {
		if (!running.get()) {
			return;
		}
		try {
			spillwayJournal.replayPendingRecords(event -> {
				UsageLedgerEntry entry = new UsageLedgerEntry(
						event.requestId(),
						event.ownerId(),
						event.provider(),
						event.model(),
						safeInt(event.promptTokens()),
						safeInt(event.completionTokens()),
						safeInt(event.totalTokens()),
						event.costUsdMicros(),
						event.durationMs(),
						event.timestamp(),
						safeInt(event.uncachedPromptTokens()),
						safeInt(event.cacheReadTokens()),
						safeInt(event.cacheWriteTokens()),
						safeInt(event.reasoningTokens()),
						event.effectiveCostMicros(),
						event.billedCostMicros(),
						event.requestHash()
				);
				repository.save(entry);
			});
		} catch (Exception ex) {
			log.debug("Spillway replay deferred: Database currently unavailable ({})", ex.getMessage());
		}
	}

	private void recordBatchMetrics(List<TokenUsageEvent> batch) {
		for (TokenUsageEvent event : batch) {
			String provider = safeTag(event.provider());
			String model = safeTag(event.model());

			if (event.promptTokens() > 0) {
				Counter.builder("aegis.tokens")
				       .baseUnit("tokens")
				       .tag("provider", provider)
				       .tag("model", model)
				       .tag("type", "prompt")
				       .register(meterRegistry)
				       .increment(event.promptTokens());
			}
			if (event.completionTokens() > 0) {
				Counter.builder("aegis.tokens")
				       .baseUnit("tokens")
				       .tag("provider", provider)
				       .tag("model", model)
				       .tag("type", "completion")
				       .register(meterRegistry)
				       .increment(event.completionTokens());
			}
			if (event.costUsdMicros() > 0) {
				Counter.builder("aegis.cost.micros")
				       .baseUnit("micros")
				       .tag("provider", provider)
				       .tag("model", model)
				       .register(meterRegistry)
				       .increment(event.costUsdMicros());
			}
		}
	}

	private static String safeTag(String value) {
		return (value == null || value.isBlank()) ? "unknown" : value;
	}

	private static int safeInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}
}
