package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpillwayJournalManager Unit Test Suite")
class SpillwayJournalManagerTest {

	@TempDir
	Path tempDir;

	private Path journalPath;
	private SpillwayJournalManager journalManager;
	private SimpleMeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		journalPath = tempDir.resolve("spillway-test.log");
		meterRegistry = new SimpleMeterRegistry();
		journalManager = new SpillwayJournalManager(
				journalPath.toString(),
				new ObjectMapper(),
				meterRegistry
		);
	}

	@Test
	@DisplayName("Should append records and replay them faithfully")
	void shouldAppendAndReplayRecords() throws IOException {
		UUID reqId1 = UUID.randomUUID();
		UUID reqId2 = UUID.randomUUID();

		TokenUsageEvent e1 = new TokenUsageEvent(
				reqId1, "tenant-a", "anthropic", "claude-sonnet-5",
				100, 50, 150, 200, 1500, Instant.now()
		);
		TokenUsageEvent e2 = new TokenUsageEvent(
				reqId2, "tenant-b", "openai", "gpt-5.6-luna",
				200, 100, 300, 400, 3000, Instant.now(),
				150, 50, 0, 10, 2800, 2800, "hash123"
		);

		journalManager.append(e1, "Database timeout");
		journalManager.append(e2, "Connection pool exhausted");

		assertThat(Files.exists(journalPath)).isTrue();
		List<String> lines = Files.readAllLines(journalPath);
		assertThat(lines).hasSize(2);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int replayedCount = journalManager.replayPendingRecords(replayed::add);

		assertThat(replayedCount).isEqualTo(2);
		assertThat(replayed).hasSize(2);
		assertThat(replayed.get(0).requestId()).isEqualTo(reqId1);
		assertThat(replayed.get(1).requestId()).isEqualTo(reqId2);
		assertThat(replayed.get(1).requestHash()).isEqualTo("hash123");
		assertThat(replayed.get(1).cacheReadTokens()).isEqualTo(50L);

		// Journal should now be empty after replay
		assertThat(Files.exists(journalPath)).isFalse();
	}

	@Test
	@DisplayName("Replay on non-existent or zero-byte journal returns zero cleanly")
	void shouldHandleNonExistentJournal() throws IOException {
		int replayed = journalManager.replayPendingRecords(e -> {
		});
		assertThat(replayed).isEqualTo(0);

		// Zero-byte file
		Files.createFile(journalPath);
		int zeroByteReplayed = journalManager.replayPendingRecords(e -> {
		});
		assertThat(zeroByteReplayed).isEqualTo(0);
	}

	@Test
	@DisplayName("Replay breaks and defers remaining events when consumer throws exception")
	void shouldBreakReplayOnConsumerFailure() {
		TokenUsageEvent e1 = new TokenUsageEvent(
				UUID.randomUUID(), null, "", "",
				10, 5, 15, 20, 100, Instant.now()
		);
		TokenUsageEvent e2 = new TokenUsageEvent(
				UUID.randomUUID(), "tenant-2", "openai", "gpt-4o",
				20, 10, 30, 40, 200, Instant.now()
		);
		journalManager.appendBatch(List.of(e1, e2), null);

		int replayed = journalManager.replayPendingRecords(e -> {
			throw new RuntimeException("DB still unreachable");
		});

		assertThat(replayed).isEqualTo(0);
		// Events should be re-appended to journal
		assertThat(Files.exists(journalPath)).isTrue();
	}

	@Test
	@DisplayName("Gracefully skips unparseable or malformed journal lines and deserializes without timestamp")
	void shouldSkipMalformedJournalLines() throws IOException {
		UUID reqId = UUID.randomUUID();
		// Line with valid requestId but no timestamp
		String noTimestampLine = "{\"requestId\":\"" + reqId + "\",\"ownerId\":\"t-notime\"}\n";
		Files.writeString(
				journalPath,
				"not-json\n{\"foo\":\"bar\"}\n   \n" + noTimestampLine,
				java.nio.charset.StandardCharsets.UTF_8
		);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int count = journalManager.replayPendingRecords(replayed::add);
		assertThat(count).isEqualTo(1);
		assertThat(replayed.getFirst().requestId()).isEqualTo(reqId);
		assertThat(replayed.getFirst().timestamp()).isNotNull();
	}

	@Test
	@DisplayName("Handles serialization and I/O failures gracefully")
	void shouldHandleSerializationAndIoFailures() throws Exception {
		ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
		org.mockito.Mockito.when(failingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
		                   .thenThrow(new RuntimeException("Simulated JSON failure"));

		SpillwayJournalManager failingMgr = new SpillwayJournalManager(
				journalPath.toString(),
				failingMapper,
				meterRegistry
		);
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "tenant", "openai", "gpt-4o",
				10, 5, 15, 20, 100, Instant.now()
		);
		failingMgr.append(event, "test error");
		assertThat(Files.exists(journalPath)).isTrue();
		List<String> lines = Files.readAllLines(journalPath);
		assertThat(lines.getFirst()).contains("serialization_failed");

		// Test appendBatch I/O exception when writing to a directory path
		Path dirAsFile = tempDir.resolve("dir-as-file");
		Files.createDirectory(dirAsFile);
		SpillwayJournalManager unwriteableMgr = new SpillwayJournalManager(
				dirAsFile.toString(),
				new ObjectMapper(),
				meterRegistry
		);
		// Writing directly to directory as file path throws IOException, which is caught and logged
		unwriteableMgr.append(event, "err");
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("Gracefully handles null events and constructor defaults")
	void shouldHandleNullBatchAndDefaults() throws IOException {
		journalManager.appendBatch(null, null);
		assertThat(Files.exists(journalPath)).isFalse();

		// Constructor with null meterRegistry
		SpillwayJournalManager nullRegMgr = new SpillwayJournalManager(
				journalPath.toString(),
				new ObjectMapper(),
				null
		);
		assertThat(nullRegMgr).isNotNull();

		// Relative path without parent
		Path relativePath = Path.of("spillway-relative-test.log");
		try {
			SpillwayJournalManager relMgr = new SpillwayJournalManager(
					relativePath.toString(),
					new ObjectMapper(),
					meterRegistry
			);
			TokenUsageEvent event = new TokenUsageEvent(
					UUID.randomUUID(), "t", null, null,
					10, 5, 15, 20, 100, Instant.now()
			);
			relMgr.append(event, null);
			assertThat(Files.exists(relativePath)).isTrue();
			relMgr.replayPendingRecords(e -> {
			});
		} finally {
			Files.deleteIfExists(relativePath);
		}
	}
}
