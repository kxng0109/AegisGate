package io.github.kxng0109.aegisgate.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UsageLedgerListener}: persistence of an event, the
 * dead letter fallback when the database fails, and the never throw
 * contract.
 */
@DisplayName("UsageLedgerListener")
class UsageLedgerListenerTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("persists a completed request")
	void persistsEvent() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(repository,
		                                                       tempDir.resolve("deadletter.log").toString());
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
	void failureWithoutMessage() throws Exception {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		when(repository.save(any(UsageLedgerEntry.class)))
				.thenThrow(new DataAccessResourceFailureException((String) null));
		Path deadLetter = tempDir.resolve("deadletter-null.log");
		UsageLedgerListener listener = new UsageLedgerListener(repository, deadLetter.toString());

		assertDoesNotThrow(() -> listener.onTokenUsage(event()));

		assertTrue(Files.exists(deadLetter));
	}

	@Test
	@DisplayName("a missing owner id becomes the unknown tenant")
	void missingOwnerId() {
		UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
		UsageLedgerListener listener = new UsageLedgerListener(repository,
		                                                       tempDir.resolve("deadletter.log").toString());
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), null, "openai", "gpt-5.6-sol",
				10, 5, 15, 100, 4200, Instant.now());

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
					1, 1, 2, 10, 100, Instant.now());

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
		UsageLedgerListener listener = new UsageLedgerListener(repository,
		                                                       tempDir.resolve("deadletter.log").toString());
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				(long) Integer.MAX_VALUE + 1, 1, 2, 10, 100, Instant.now());

		listener.onTokenUsage(event);

		verify(repository).save(argThat(entry -> entry.getPromptTokens() == Integer.MAX_VALUE));
	}

	private static TokenUsageEvent event() {
		return new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				10, 5, 15, 100, 4200, Instant.now());
	}
}