package io.github.kxng0109.aegisgate.ledger;

import lombok.extern.slf4j.Slf4j;
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
 * Writes {@link TokenUsageEvent} records to the ledger database.
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

	/**
	 * @param repository     the ledger repository
	 * @param deadLetterPath where failed records are appended, one per line
	 */
	public UsageLedgerListener(
			UsageLedgerRepository repository,
			@Value("${gateway.ledger.dead-letter-path:logs/ledger-deadletter.log}") String deadLetterPath
	) {
		this.repository = repository;
		this.deadLetterPath = deadLetterPath;
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
			log.debug("Recorded usage for request {} against provider {}", event.requestId(), event.provider());
		} catch (RuntimeException ex) {
			log.warn("Could not persist usage for request {}: {}", event.requestId(), ex.getMessage());
			appendToDeadLetter(event, ex);
		}
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