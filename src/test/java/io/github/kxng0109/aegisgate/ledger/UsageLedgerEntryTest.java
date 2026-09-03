package io.github.kxng0109.aegisgate.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UsageLedgerEntry & ModelPricingEntry Coverage Test Suite")
class UsageLedgerEntryTest {

	@Test
	@DisplayName("UsageLedgerEntry constructors and getters behave deterministically")
	void testUsageLedgerEntryConstructors() {
		UUID reqId = UUID.randomUUID();
		Instant now = Instant.now();

		// Backwards-compatible constructor
		UsageLedgerEntry entry1 = new UsageLedgerEntry(
				reqId, null, "openai", "gpt-5.6-luna",
				100, 50, 150, 1200, 300, now
		);

		assertThat(entry1.getOwnerId()).isEqualTo("unknown");
		assertThat(entry1.getUncachedPromptTokens()).isEqualTo(100);
		assertThat(entry1.getCacheReadTokens()).isEqualTo(0);
		assertThat(entry1.getCacheWriteTokens()).isEqualTo(0);
		assertThat(entry1.getReasoningTokens()).isEqualTo(0);
		assertThat(entry1.getEffectiveCostMicros()).isEqualTo(1200L);
		assertThat(entry1.getBilledCostMicros()).isEqualTo(1200L);
		assertThat(entry1.getRequestHash()).isNull();

		// Full FinOps constructor
		UsageLedgerEntry entry2 = new UsageLedgerEntry(
				reqId, "tenant-1", "anthropic", "claude-sonnet-5",
				1000, 500, 1500, 7000, 450, now,
				200, 500, 300, 80, 6250, 6250, "hash_xyz"
		);

		assertThat(entry2.getOwnerId()).isEqualTo("tenant-1");
		assertThat(entry2.getProvider()).isEqualTo("anthropic");
		assertThat(entry2.getModel()).isEqualTo("claude-sonnet-5");
		assertThat(entry2.getPromptTokens()).isEqualTo(1000);
		assertThat(entry2.getCompletionTokens()).isEqualTo(500);
		assertThat(entry2.getTotalTokens()).isEqualTo(1500);
		assertThat(entry2.getCostUsdMicros()).isEqualTo(7000L);
		assertThat(entry2.getDurationMs()).isEqualTo(450L);
		assertThat(entry2.getCreatedAt()).isEqualTo(now);
		assertThat(entry2.getUncachedPromptTokens()).isEqualTo(200);
		assertThat(entry2.getCacheReadTokens()).isEqualTo(500);
		assertThat(entry2.getCacheWriteTokens()).isEqualTo(300);
		assertThat(entry2.getReasoningTokens()).isEqualTo(80);
		assertThat(entry2.getEffectiveCostMicros()).isEqualTo(6250L);
		assertThat(entry2.getBilledCostMicros()).isEqualTo(6250L);
		assertThat(entry2.getRequestHash()).isEqualTo("hash_xyz");

		// No-arg constructor for JPA
		UsageLedgerEntry entry3 = new UsageLedgerEntry();
		assertThat(entry3.getId()).isNull();
	}

	@Test
	@DisplayName("ModelPricingEntry constructors and factory behave deterministically")
	void testModelPricingEntry() {
		ModelPricingEntry entry1 = new ModelPricingEntry(
				"gpt-5.6-luna", "openai", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000010")
		);
		assertThat(entry1.cacheReadInputTokenCost()).isNull();
		assertThat(entry1.cacheCreationInputTokenCost()).isNull();

		ModelPricingEntity entity = new ModelPricingEntity(
				"claude-sonnet-5", "anthropic", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000010"),
				new BigDecimal("0.0000002"), new BigDecimal("0.0000025"),
				1000000L, 128000L, "http://source", Instant.now()
		);
		ModelPricingEntry fromEntity = ModelPricingEntry.from(entity);
		assertThat(fromEntity.cacheReadInputTokenCost()).isEqualTo(new BigDecimal("0.0000002"));
		assertThat(fromEntity.cacheCreationInputTokenCost()).isEqualTo(new BigDecimal("0.0000025"));
	}
}
