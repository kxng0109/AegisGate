package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Resilient, append-only disk spillway journal protecting usage and FinOps billing records from data loss during
 * database connectivity outages or downstream queue saturation.
 */
@Slf4j
@Component
public class SpillwayJournalManager {

	private final Path journalPath;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Creates a new spillway journal manager.
	 *
	 * @param deadLetterPath configured file path for dead letter / spillway journal
	 * @param objectMapper   Jackson object mapper for JSON serialization
	 * @param meterRegistry  metrics registry for tracking spillway writes and replays
	 */
	@Autowired
	public SpillwayJournalManager(
			@Value("${gateway.ledger.dead-letter-path:logs/ledger-deadletter.log}") String deadLetterPath,
			ObjectMapper objectMapper,
			@Nullable MeterRegistry meterRegistry
	) {
		this.journalPath = Path.of(deadLetterPath);
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Appends a single usage event to the durable disk journal.
	 *
	 * @param event the usage event to persist
	 * @param error optional error description
	 */
	public void append(TokenUsageEvent event, @Nullable String error) {
		appendBatch(List.of(event), error);
	}

	/**
	 * Appends a batch of usage events to the durable disk journal.
	 *
	 * @param events the batch of events to persist
	 * @param error  optional error description
	 */
	public void appendBatch(List<TokenUsageEvent> events, @Nullable String error) {
		if (events == null || events.isEmpty()) {
			return;
		}
		lock.lock();
		try {
			if (journalPath.getParent() != null) {
				Files.createDirectories(journalPath.getParent());
			}
			StringBuilder sb = new StringBuilder();
			for (TokenUsageEvent event : events) {
				String line = serializeEvent(event, error);
				sb.append(line).append('\n');
				recordSpillwayMetric(event.provider());
			}
			Files.writeString(
					journalPath,
					sb.toString(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND,
					StandardOpenOption.WRITE
			);
		} catch (IOException ex) {
			log.error(
					"Catastrophic failure: Unable to append {} events to spillway journal at {}: {}",
					events.size(), journalPath, ex.getMessage()
			);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Replays pending journal entries through a consumer and clears the replayed entries.
	 *
	 * @param consumer processor for deserialized usage events
	 * @return count of successfully replayed records
	 */
	public int replayPendingRecords(Consumer<TokenUsageEvent> consumer) {
		if (!Files.exists(journalPath)) {
			return 0;
		}

		lock.lock();
		try {
			if (!Files.exists(journalPath) || Files.size(journalPath) == 0) {
				return 0;
			}

			Path stagingPath = Path.of(journalPath + ".replay." + System.nanoTime());
			Files.move(journalPath, stagingPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

			List<TokenUsageEvent> recovered = new ArrayList<>();
			try (BufferedReader reader = Files.newBufferedReader(stagingPath, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (trimmed.isEmpty()) {
						continue;
					}
					TokenUsageEvent event = deserializeEvent(trimmed);
					if (event != null) {
						recovered.add(event);
					}
				}
			}

			int replayedCount = 0;
			try {
				for (int i = 0; i < recovered.size(); i++) {
					TokenUsageEvent event = recovered.get(i);
					try {
						consumer.accept(event);
						replayedCount++;
						recordReplayMetric(event.provider());
					} catch (RuntimeException ex) {
						log.warn(
								"Replay failed for request {}: {}; stopping replay cycle",
								event.requestId(),
								ex.getMessage()
						);
						// Put back this failed event and all remaining unattempted events
						List<TokenUsageEvent> remaining = recovered.subList(i, recovered.size());
						appendBatch(remaining, "Replay deferred: " + ex.getMessage());
						break;
					}
				}
			} finally {
				// The staging file must always be removed, even when replay defers events.
				Files.deleteIfExists(stagingPath);
			}

			log.info("Successfully replayed {} usage records from spillway journal", replayedCount);
			return replayedCount;
		} catch (IOException ex) {
			log.error("Failed to process replay on journal {}: {}", journalPath, ex.getMessage());
			return 0;
		} finally {
			lock.unlock();
		}
	}

	private String serializeEvent(TokenUsageEvent event, @Nullable String error) {
		try {
			return objectMapper.writeValueAsString(new SpillwayRecord(
					event.requestId().toString(),
					event.ownerId(),
					event.provider(),
					event.model(),
					event.promptTokens(),
					event.completionTokens(),
					event.totalTokens(),
					event.durationMs(),
					event.costUsdMicros(),
					event.timestamp().toString(),
					event.uncachedPromptTokens(),
					event.cacheReadTokens(),
					event.cacheWriteTokens(),
					event.reasoningTokens(),
					event.effectiveCostMicros(),
					event.billedCostMicros(),
					event.requestHash(),
					error != null ? error : "unknown"
			));
		} catch (Exception ex) {
			return "{\"requestId\":\"" + event.requestId() + "\",\"error\":\"serialization_failed\"}";
		}
	}

	private @Nullable TokenUsageEvent deserializeEvent(String jsonLine) {
		try {
			JsonNode node = objectMapper.readTree(jsonLine);
			if (!node.has("requestId")) {
				return null;
			}
			UUID requestId = UUID.fromString(node.get("requestId").asString());
			String ownerId = node.path("ownerId").asString("unknown");
			String provider = node.path("provider").asString("unknown");
			String model = node.path("model").asString("unknown");
			long promptTokens = node.path("promptTokens").asLong(0);
			long completionTokens = node.path("completionTokens").asLong(0);
			long totalTokens = node.path("totalTokens").asLong(promptTokens + completionTokens);
			long durationMs = node.path("durationMs").asLong(0);
			long costUsdMicros = node.path("costUsdMicros").asLong(0);
			Instant timestamp = node.has("timestamp")
					? Instant.parse(node.get("timestamp").asString())
					: Instant.now();
			long uncachedPromptTokens = node.path("uncachedPromptTokens").asLong(promptTokens);
			long cacheReadTokens = node.path("cacheReadTokens").asLong(0);
			long cacheWriteTokens = node.path("cacheWriteTokens").asLong(0);
			long reasoningTokens = node.path("reasoningTokens").asLong(0);
			long effectiveCostMicros = node.path("effectiveCostMicros").asLong(costUsdMicros);
			long billedCostMicros = node.path("billedCostMicros").asLong(costUsdMicros);
			String requestHash = node.has("requestHash") && !node.get("requestHash").isNull()
					? node.get("requestHash").asString()
					: null;

			return new TokenUsageEvent(
					requestId, ownerId, provider, model,
					promptTokens, completionTokens, totalTokens,
					durationMs, costUsdMicros, timestamp,
					uncachedPromptTokens, cacheReadTokens, cacheWriteTokens,
					reasoningTokens, effectiveCostMicros, billedCostMicros, requestHash
			);
		} catch (Exception ex) {
			log.warn("Failed to deserialize spillway journal line: {}", ex.getMessage());
			return null;
		}
	}

	private void recordSpillwayMetric(String provider) {
		Counter.builder("aegis.ledger.dead_letter")
		       .baseUnit("records")
		       .tag("provider", provider != null && !provider.isBlank() ? provider : "unknown")
		       .register(meterRegistry)
		       .increment();
	}

	private void recordReplayMetric(String provider) {
		Counter.builder("aegis.ledger.spillway.replayed")
		       .baseUnit("records")
		       .tag("provider", provider != null && !provider.isBlank() ? provider : "unknown")
		       .register(meterRegistry)
		       .increment();
	}

	private record SpillwayRecord(
			String requestId,
			@Nullable String ownerId,
			String provider,
			String model,
			long promptTokens,
			long completionTokens,
			long totalTokens,
			long durationMs,
			long costUsdMicros,
			String timestamp,
			long uncachedPromptTokens,
			long cacheReadTokens,
			long cacheWriteTokens,
			long reasoningTokens,
			long effectiveCostMicros,
			long billedCostMicros,
			@Nullable String requestHash,
			String error
	) {
	}
}
