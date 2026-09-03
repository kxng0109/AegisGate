package io.github.kxng0109.aegisgate.ledger.queue;

import io.github.kxng0109.aegisgate.ledger.SpillwayJournalManager;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("DisruptorUsageLedgerQueue Unit Test Suite")
class DisruptorUsageLedgerQueueTest {

	private SpillwayJournalManager spillwayJournal;
	private DisruptorUsageLedgerQueue queue;

	@BeforeEach
	void setUp() {
		spillwayJournal = mock(SpillwayJournalManager.class);
		// Small power-of-two queue for testing bounds: capacity = 1024
		queue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
	}

	@Test
	@DisplayName("Should offer and drain events in FIFO order and respect maxElements bound")
	void shouldOfferAndDrainEvents() throws Exception {
		TokenUsageEvent e1 = createEvent("tenant-1");
		TokenUsageEvent e2 = createEvent("tenant-2");

		assertThat(queue.offer(e1)).isTrue();
		assertThat(queue.offer(e2)).isTrue();
		assertThat(queue.size()).isEqualTo(2);

		// Drain only 1 element (tests drained == maxElements branch)
		List<TokenUsageEvent> partialDrain = new ArrayList<>();
		int partialCount = queue.drainTo(partialDrain, 1);
		assertThat(partialCount).isEqualTo(1);
		assertThat(partialDrain).containsExactly(e1);
		assertThat(queue.size()).isEqualTo(1);

		// Drain remaining
		List<TokenUsageEvent> remainingDrain = new ArrayList<>();
		int remainingCount = queue.drainTo(remainingDrain, 10);
		assertThat(remainingCount).isEqualTo(1);
		assertThat(remainingDrain).containsExactly(e2);
		assertThat(queue.size()).isEqualTo(0);

		// Test in-flight slot (event == null branch)
		java.lang.reflect.Field prodSeqField = DisruptorUsageLedgerQueue.class.getDeclaredField("producerSequence");
		prodSeqField.setAccessible(true);
		java.util.concurrent.atomic.AtomicLong prodSeq = (java.util.concurrent.atomic.AtomicLong) prodSeqField.get(queue);

		java.lang.reflect.Field consSeqField = DisruptorUsageLedgerQueue.class.getDeclaredField("consumerSequence");
		consSeqField.setAccessible(true);
		java.util.concurrent.atomic.AtomicLong consSeq = (java.util.concurrent.atomic.AtomicLong) consSeqField.get(queue);

		// Simulate head < tail but buffer slot is null (in-flight producer)
		consSeq.set(0);
		prodSeq.set(1);
		List<TokenUsageEvent> inFlightDrain = new ArrayList<>();
		int inFlightCount = queue.drainTo(inFlightDrain, 10);
		assertThat(inFlightCount).isEqualTo(0); // breaks out because slot event is null
	}

	@Test
	@DisplayName("Should overflow to spillway journal when ring buffer is saturated")
	void shouldOverflowWhenSaturated() {
		// Create a tiny queue with capacity 1024
		DisruptorUsageLedgerQueue smallQueue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);

		for (int i = 0; i < 1024; i++) {
			smallQueue.offer(createEvent("tenant-" + i));
		}

		// 1025th event must trigger spillway overflow
		TokenUsageEvent overflowEvent = createEvent("tenant-overflow");
		boolean offered = smallQueue.offer(overflowEvent);

		assertThat(offered).isFalse();
		verify(spillwayJournal).append(any(TokenUsageEvent.class), anyString());
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("Offering null returns false without throwing exception")
	void shouldRejectNullOffer() {
		assertThat(queue.offer(null)).isFalse();
	}

	@Test
	@DisplayName("Capacity rounding and accessors behave deterministically")
	void shouldTestCapacityCalculations() {
		// Exact power of two
		DisruptorUsageLedgerQueue q1 = new DisruptorUsageLedgerQueue(2048, spillwayJournal);
		assertThat(q1.capacity()).isEqualTo(2048);

		// Non-power of two rounds up
		DisruptorUsageLedgerQueue q2 = new DisruptorUsageLedgerQueue(1500, spillwayJournal);
		assertThat(q2.capacity()).isEqualTo(2048);

		// Below minimum rounds up to 1024
		DisruptorUsageLedgerQueue q3 = new DisruptorUsageLedgerQueue(100, spillwayJournal);
		assertThat(q3.capacity()).isEqualTo(1024);
	}

	private static TokenUsageEvent createEvent(String tenant) {
		return new TokenUsageEvent(
				UUID.randomUUID(), tenant, "openai", "gpt-5.6-sol",
				100, 50, 150, 120, 1000, Instant.now()
		);
	}
}
