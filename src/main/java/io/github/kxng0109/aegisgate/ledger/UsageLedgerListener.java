package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes {@link TokenUsageEvent} records to the ledger database and records Micrometer metrics.
 *
 * <p>The listener runs on the dedicated {@code ledgerExecutor} because of the
 * {@code @Async} annotation, so the streaming thread that published the event never waits on a database write. The
 * whole body is guarded: a database outage is logged and the record is appended to a dead letter file instead of being
 * rethrown, because an exception from an asynchronous listener would be silently swallowed anyway and the usage record
 * would be lost entirely. The gateway's hot path never depends on this listener succeeding.</p>
 */
@Slf4j
@Component
public class UsageLedgerListener {

	private final UsageLedgerRepository repository;
	private final String deadLetterPath;
	private final MeterRegistry meterRegistry;

	/**
	 * @param repository     the ledger repository
	 * @param deadLetterPath where failed records are appended, one per line
	 * @param meterRegistry  the metrics registry for tracking token throughput and cost
	 */
	@Autowired
	public UsageLedgerListener(
			UsageLedgerRepository repository,
			@Value("${gateway.ledger.dead-letter-path:logs/ledger-deadletter.log}") String deadLetterPath,
			MeterRegistry meterRegistry
	) {
		this.repository = repository;
		this.deadLetterPath = deadLetterPath;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Convenience constructor for testing or environments where a custom registry is not provided.
	 *
	 * @param repository     the ledger repository
	 * @param deadLetterPath where failed records are appended, one per line
	 */
	public UsageLedgerListener(UsageLedgerRepository repository, String deadLetterPath) {
		this(repository, deadLetterPath, new SimpleMeterRegistry());
	}

	/**
	 * Persists one completed request.
	 *
	 * @param event the usage and cost record
	 */
	@EventListener
	@Async("ledgerExecutor")
	public void onTokenUsage(TokenUsageEvent event) {
		try {
			if (repository.existsByRequestId(event.requestId())) {
				log.debug("Usage for request {} was already recorded; skipping", event.requestId());
				return;
			}
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
					event.timestamp()
			);
			repository.save(entry);
			recordMetrics(event);
			log.debug("Recorded usage for request {} against provider {}", event.requestId(), event.provider());
		} catch (RuntimeException ex) {
			log.warn("Could not persist usage for request {}: {}", event.requestId(), ex.getMessage());
			appendToDeadLetter(event, ex);
			recordDeadLetterMetric(event.provider());
		}
	}

	private void recordMetrics(TokenUsageEvent event) {
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

	private void recordDeadLetterMetric(String provider) {
		Counter.builder("aegis.ledger.dead_letter")
		       .baseUnit("records")
		       .tag("provider", safeTag(provider))
		       .register(meterRegistry)
		       .increment();
	}

	private static String safeTag(String value) {
		return (value == null || value.isBlank()) ? "unknown" : value;
	}

	private void appendToDeadLetter(TokenUsageEvent event, RuntimeException cause) {
		try {
			Path path = Path.of(deadLetterPath);
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			String line = "{\"requestId\":\"" + event.requestId()
					+ "\",\"ownerId\":\"" + (event.ownerId() == null ? "" : event.ownerId())
					+ "\",\"provider\":\"" + event.provider()
					+ "\",\"model\":\"" + event.model()
					+ "\",\"promptTokens\":" + event.promptTokens()
					+ ",\"completionTokens\":" + event.completionTokens()
					+ ",\"costUsdMicros\":" + event.costUsdMicros()
					+ ",\"error\":\"" + (cause.getMessage() == null ? "unknown" : cause.getMessage()) + "\"}\n";
			Files.writeString(
					path, line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
			);
		} catch (IOException writeFailure) {
			log.error(
					"Could not write the usage record to the dead letter file {}: {}",
					deadLetterPath, writeFailure.getMessage()
			);
		}
	}

	private static int safeInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}
}