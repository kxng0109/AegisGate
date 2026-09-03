package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UsageLedgerListener}: persistence of an event, the dead letter fallback when the database
 * fails, and the never throw contract.
 */
@DisplayName("UsageLedgerListener")
@SuppressWarnings("DataFlowIssue")
class UsageLedgerListenerTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("persists a completed request")
	void persistsEvent() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString()
		);
		TokenUsageEvent event = event();

		listener.onTokenUsage(event);

		verify(repository).save(any(UsageLedgerEntry.class));
	}

	@Test
	@DisplayName("a database failure writes the dead letter and never throws")
	void databaseFailureWritesDeadLetter() throws Exception {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException("db down"));
		Path deadLetter = tempDir.resolve("deadletter.log");
		UsageLedgerListener listener = new UsageLedgerListener(repository, deadLetter.toString());

		TokenUsageEvent event = event();
		assertDoesNotThrow(() -> listener.onTokenUsage(event));

		assertTrue(Files.exists(deadLetter));
		String content = Files.readString(deadLetter);
		assertTrue(content.contains(event.requestId().toString()));
		assertTrue(content.contains("\"provider\":\"openai\""));
	}

	@Test
	@DisplayName("an unwritable dead letter file is logged, never thrown")
	void unwritableDeadLetterIsSwallowed() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException("db down"));
		Path unwritable = tempDir.resolve("dead-letter-dir");
		assertDoesNotThrow(() -> Files.createDirectory(unwritable));
		UsageLedgerListener listener = new UsageLedgerListener(repository, unwritable.toString());

		assertDoesNotThrow(() -> listener.onTokenUsage(event()));
	}

	@Test
	@DisplayName("a failure without a message still writes the dead letter")
	void failureWithoutMessage() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException(null));
		Path deadLetter = tempDir.resolve("deadletter-null.log");
		UsageLedgerListener listener = new UsageLedgerListener(repository, deadLetter.toString());

		assertDoesNotThrow(() -> listener.onTokenUsage(event()));

		assertTrue(Files.exists(deadLetter));
	}

	@Test
	@DisplayName("a missing owner id becomes the unknown tenant")
	void missingOwnerId() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString()
		);
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), null, "openai", "gpt-5.6-sol",
				10, 5, 15, 100, 4200, Instant.now()
		);

		listener.onTokenUsage(event);

		verify(repository).save(argThat(entry -> "unknown".equals(entry.getOwnerId())));
	}

	@Test
	@DisplayName("a relative dead letter path skips directory creation")
	void relativeDeadLetterPath() throws Exception {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException("db down"));
		Path deadLetter = Path.of("ledger-deadletter-test.log");
		try {
			UsageLedgerListener listener = new UsageLedgerListener(repository, deadLetter.toString());
			TokenUsageEvent event = new TokenUsageEvent(
					UUID.randomUUID(), null, "openai", "gpt-5.6-sol",
					1, 1, 2, 10, 100, Instant.now()
			);

			assertDoesNotThrow(() -> listener.onTokenUsage(event));

			assertTrue(Files.exists(deadLetter));
		} finally {
			Files.deleteIfExists(deadLetter);
		}
	}

	@Test
	@DisplayName("token counts above the integer range are clamped")
	void clampsOversizedTokenCounts() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString()
		);
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				(long) Integer.MAX_VALUE + 1, 1, 2, 10, 100, Instant.now()
		);

		listener.onTokenUsage(event);

		verify(repository).save(argThat(entry -> entry.getPromptTokens() == Integer.MAX_VALUE));
	}

	@Test
	@DisplayName("records Micrometer metrics for tokens, cost, and dead letters")
	void recordsMicrometerMetrics() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString(),
				registry
		);

		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				100, 50, 150, 1200, 2500, Instant.now()
		);

		listener.onTokenUsage(event);

		assertThat(registry.get("aegis.tokens").tag("type", "prompt").tag("provider", "openai").counter().count())
				.isEqualTo(100.0);
		assertThat(registry.get("aegis.tokens").tag("type", "completion").tag("provider", "openai").counter().count())
				.isEqualTo(50.0);
		assertThat(registry.get("aegis.cost.micros").tag("provider", "openai").counter().count())
				.isEqualTo(2500.0);
	}

	@Test
	@DisplayName("records dead letter metric when database save fails")
	void recordsDeadLetterMetricOnFailure() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException("db down"));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString(),
				registry
		);

		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "anthropic", "claude-sonnet-5",
				10, 20, 30, 500, 800, Instant.now()
		);

		listener.onTokenUsage(event);

		assertThat(registry.get("aegis.ledger.dead_letter").tag("provider", "anthropic").counter().count())
				.isEqualTo(1.0);
	}

	@Test
	@DisplayName("handles null registry and blank provider/model gracefully in metrics")
	void handlesNullRegistryAndBlankProvider() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString(),
				null
		);

		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "", "   ",
				0, 0, 0, 0, 100, Instant.now()
		);

		assertDoesNotThrow(() -> listener.onTokenUsage(event));
	}

	@Test
	@DisplayName("handles null provider and model in metrics and dead letter")
	void handlesNullProviderAndModelInMetrics() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		UsageLedgerListener listener = new UsageLedgerListener(
				repository,
				tempDir.resolve("deadletter.log").toString(),
				registry
		);

		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", null, null,
				10, 5, 15, 100, 200, Instant.now()
		);

		listener.onTokenUsage(event);

		assertThat(registry.get("aegis.tokens").tag("type", "prompt").tag("provider", "unknown").tag("model", "unknown")
		                   .counter().count())
				.isEqualTo(10.0);

		// Also trigger dead letter with null provider
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException("db down"));

		TokenUsageEvent failureEvent = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", null, null,
				0, 0, 0, 100, 0, Instant.now()
		);

		listener.onTokenUsage(failureEvent);

		assertThat(registry.get("aegis.ledger.dead_letter").tag("provider", "unknown").counter().count())
				.isEqualTo(1.0);
	}

	private static TokenUsageEvent event() {
		return new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				10, 5, 15, 100, 4200, Instant.now()
		);
	}
}