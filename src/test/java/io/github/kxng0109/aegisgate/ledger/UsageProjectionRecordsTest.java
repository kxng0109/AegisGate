package io.github.kxng0109.aegisgate.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Usage Projection Records")
class UsageProjectionRecordsTest {

	@Test
	@DisplayName("UsageTotals preserves constructor fields")
	void usageTotalsFields() {
		UsageTotals totals = new UsageTotals(5L, 500L, 250L, 750L, 7_000L, 110.0);
		assertThat(totals.totalRequests()).isEqualTo(5L);
		assertThat(totals.totalPromptTokens()).isEqualTo(500L);
		assertThat(totals.totalCompletionTokens()).isEqualTo(250L);
		assertThat(totals.totalTokens()).isEqualTo(750L);
		assertThat(totals.totalCostUsdMicros()).isEqualTo(7_000L);
		assertThat(totals.averageDurationMs()).isEqualTo(110.0);
	}

	@Test
	@DisplayName("OwnerUsageRecord preserves constructor fields")
	void ownerUsageRecordFields() {
		OwnerUsageRecord record = new OwnerUsageRecord("tenant-a", 3L, 300L, 150L, 450L, 4_200L, 95.0);
		assertThat(record.ownerId()).isEqualTo("tenant-a");
		assertThat(record.totalRequests()).isEqualTo(3L);
		assertThat(record.totalPromptTokens()).isEqualTo(300L);
		assertThat(record.totalCompletionTokens()).isEqualTo(150L);
		assertThat(record.totalTokens()).isEqualTo(450L);
		assertThat(record.totalCostUsdMicros()).isEqualTo(4_200L);
		assertThat(record.averageDurationMs()).isEqualTo(95.0);
	}

	@Test
	@DisplayName("ModelUsageRecord preserves constructor fields")
	void modelUsageRecordFields() {
		ModelUsageRecord record = new ModelUsageRecord("openai", "gpt-4o", 2L, 200L, 100L, 300L, 2_800L, 105.0);
		assertThat(record.provider()).isEqualTo("openai");
		assertThat(record.model()).isEqualTo("gpt-4o");
		assertThat(record.totalRequests()).isEqualTo(2L);
		assertThat(record.totalPromptTokens()).isEqualTo(200L);
		assertThat(record.totalCompletionTokens()).isEqualTo(100L);
		assertThat(record.totalTokens()).isEqualTo(300L);
		assertThat(record.totalCostUsdMicros()).isEqualTo(2_800L);
		assertThat(record.averageDurationMs()).isEqualTo(105.0);
	}

	@Test
	@DisplayName("ProviderUsageRecord preserves constructor fields")
	void providerUsageRecordFields() {
		ProviderUsageRecord record = new ProviderUsageRecord("anthropic", 4L, 400L, 200L, 600L, 5_600L, 115.0);
		assertThat(record.provider()).isEqualTo("anthropic");
		assertThat(record.totalRequests()).isEqualTo(4L);
		assertThat(record.totalPromptTokens()).isEqualTo(400L);
		assertThat(record.totalCompletionTokens()).isEqualTo(200L);
		assertThat(record.totalTokens()).isEqualTo(600L);
		assertThat(record.totalCostUsdMicros()).isEqualTo(5_600L);
		assertThat(record.averageDurationMs()).isEqualTo(115.0);
	}
}
