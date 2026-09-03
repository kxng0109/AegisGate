package io.github.kxng0109.aegisgate.security.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MerkleAuditLedger Tests")
class MerkleAuditLedgerTest {

	private final MerkleAuditLedger ledger = new MerkleAuditLedger();

	@Test
	@DisplayName("recordTransaction produces compliant non-repudiation receipt with valid 64-hex hashes")
	void recordsTransactionAndProducesReceipt() {
		byte[] prompt = "{\"prompt\": \"Hello AegisGate\"}".getBytes(StandardCharsets.UTF_8);
		String responseHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

		MerkleAuditLedger.AuditReceipt receipt = ledger.recordTransaction(
				"tenant-123",
				"key-hash-abc",
				prompt,
				responseHash
		);

		assertThat(receipt).isNotNull();
		assertThat(receipt.leafHash()).hasSize(64);
		assertThat(receipt.chainHash()).hasSize(64);
		assertThat(receipt.signature()).hasSize(64);

		// Format: leaf[0..15]:chain[0..15]:sig[0..15] -> 16 + 1 + 16 + 1 + 16 = 50 characters
		assertThat(receipt.receiptHeaderValue()).hasSize(50);
		String[] parts = receipt.receiptHeaderValue().split(":");
		assertThat(parts).hasSize(3);
		assertThat(parts[0]).isEqualTo(receipt.leafHash().substring(0, 16));
		assertThat(parts[1]).isEqualTo(receipt.chainHash().substring(0, 16));
		assertThat(parts[2]).isEqualTo(receipt.signature().substring(0, 16));
	}

	@Test
	@DisplayName("recordTransaction applies defaults when parameters are null")
	void recordsTransactionWithNullDefaults() {
		MerkleAuditLedger.AuditReceipt receipt = ledger.recordTransaction(null, null, null, null);
		assertThat(receipt).isNotNull();
		assertThat(receipt.leafHash()).hasSize(64);
		assertThat(receipt.chainHash()).hasSize(64);
		assertThat(receipt.signature()).hasSize(64);
	}

	@Test
	@DisplayName("forward-security: consecutive transactions advance hash chain monotonically")
	void forwardSecureChainAdvancement() {
		byte[] prompt1 = "Request 1".getBytes(StandardCharsets.UTF_8);
		byte[] prompt2 = "Request 2".getBytes(StandardCharsets.UTF_8);

		MerkleAuditLedger.AuditReceipt r1 = ledger.recordTransaction("tenant-1", "key-1", prompt1, "hash1");
		MerkleAuditLedger.AuditReceipt r2 = ledger.recordTransaction("tenant-1", "key-1", prompt2, "hash2");

		assertThat(r1.leafHash()).isNotEqualTo(r2.leafHash());
		assertThat(r1.chainHash()).isNotEqualTo(r2.chainHash());
	}

	@Test
	@DisplayName("thread-safe atomic CAS loop executes under high concurrency on Virtual Threads")
	void concurrentVirtualThreadsRecording() throws Exception {
		int concurrency = 50;
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Callable<MerkleAuditLedger.AuditReceipt>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrency; i++) {
				final int idx = i;
				tasks.add(() -> ledger.recordTransaction(
						"tenant-" + idx,
						"key-" + idx,
						("Prompt " + idx).getBytes(StandardCharsets.UTF_8),
						"resp-" + idx
				));
			}

			List<Future<MerkleAuditLedger.AuditReceipt>> futures = executor.invokeAll(tasks);
			for (Future<MerkleAuditLedger.AuditReceipt> future : futures) {
				MerkleAuditLedger.AuditReceipt receipt = future.get();
				assertThat(receipt.receiptHeaderValue()).hasSize(50);
			}
		}
	}
}
