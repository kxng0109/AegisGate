package io.github.kxng0109.aegisgate.ledger.queue;

import io.github.kxng0109.aegisgate.ledger.SpillwayJournalManager;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.ledger.UsageLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("MicroBatchLedgerWriter Unit Test Suite")
class MicroBatchLedgerWriterTest {

	private DisruptorUsageLedgerQueue queue;
	private UsageLedgerRepository repository;
	private SpillwayJournalManager spillwayJournal;
	private MicroBatchLedgerWriter writer;

	@BeforeEach
	void setUp() {
		spillwayJournal = mock(SpillwayJournalManager.class);
		queue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		repository = mock(UsageLedgerRepository.class);
		writer = new MicroBatchLedgerWriter(
				queue,
				repository,
				spillwayJournal,
				new SimpleMeterRegistry(),
				100,
				50
		);
	}

	@AfterEach
	void tearDown() {
		if (writer.isRunning()) {
			writer.stop();
		}
	}

	@Test
	@DisplayName("Should start and stop lifecycle properly and handle idempotent calls")
	void shouldManageLifecycle() {
		assertThat(writer.isRunning()).isFalse();
		writer.stop(); // stop when not running

		writer.start();
		assertThat(writer.isRunning()).isTrue();
		writer.start(); // second start is idempotent

		writer.stop();
		assertThat(writer.isRunning()).isFalse();

		// Constructor with null registry
		MicroBatchLedgerWriter nullRegWriter = new MicroBatchLedgerWriter(
				queue, repository, spillwayJournal, null, 100, 50
		);
		assertThat(nullRegWriter).isNotNull();
	}

	@Test
	@DisplayName("Flush cycle successfully drains queue and persists batch")
	void shouldFlushBatchSuccessfully() {
		TokenUsageEvent e1 = createEvent("tenant-1");
		TokenUsageEvent e2 = createEvent("tenant-2");
		queue.offer(e1);
		queue.offer(e2);

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(2);
		verify(repository).saveAll(anyList());
		verifyNoInteractions(spillwayJournal);
	}

	@Test
	@DisplayName("Flush cycle diverts to spillway journal on database error")
	void shouldSpillToDiskOnDatabaseFailure() {
		TokenUsageEvent e1 = createEvent("tenant-fail");
		queue.offer(e1);

		doThrow(new RuntimeException("PostgreSQL connection refused"))
				.when(repository).saveAll(anyList());

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(0);
		verify(spillwayJournal).appendBatch(anyList(), anyString());
	}

	@Test
	@DisplayName("Flush cycle on empty queue returns zero cleanly")
	void shouldHandleEmptyQueueFlush() {
		int flushed = writer.flushCycle();
		assertThat(flushed).isEqualTo(0);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("Replay cycle executes when running and skips when stopped")
	void shouldExecuteReplayCycle() {
		// When stopped
		writer.replayCycle();
		verifyNoInteractions(spillwayJournal);

		// When running
		writer.start();
		doAnswer(invocation -> {
			java.util.function.Consumer<TokenUsageEvent> consumer = invocation.getArgument(0);
			consumer.accept(createEvent("replayed-tenant"));
			return 1;
		}).when(spillwayJournal).replayPendingRecords(any());

		writer.replayCycle();
		verify(repository).save(any(io.github.kxng0109.aegisgate.ledger.UsageLedgerEntry.class));

		// When replay throws exception
		doThrow(new RuntimeException("DB unreachable")).when(spillwayJournal).replayPendingRecords(any());
		writer.replayCycle(); // should log debug and not throw
	}

	@Test
	@DisplayName("Handles zero-token, zero-cost, and oversized token metrics cleanly")
	void shouldHandleZeroAndOversizedMetrics() {
		TokenUsageEvent zeroEvent = new TokenUsageEvent(
				UUID.randomUUID(), "tenant-zero", "", "   ",
				0, 0, 0, 0, 0, Instant.now()
		);
		TokenUsageEvent oversizedEvent = new TokenUsageEvent(
				UUID.randomUUID(), "tenant-huge", null, null,
				Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 100, 5000, Instant.now()
		);
		queue.offer(zeroEvent);
		queue.offer(oversizedEvent);

		int flushed = writer.flushCycle();
		assertThat(flushed).isEqualTo(2);
		verify(repository).saveAll(anyList());
	}

	private static TokenUsageEvent createEvent(String tenant) {
		return new TokenUsageEvent(
				UUID.randomUUID(), tenant, "openai", "gpt-5.6-luna",
				100, 50, 150, 200, 1500, Instant.now()
		);
	}
}
