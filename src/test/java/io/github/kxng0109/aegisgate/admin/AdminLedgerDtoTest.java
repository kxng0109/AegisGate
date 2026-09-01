package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin Ledger DTOs")
@SuppressWarnings("DataFlowIssue")
class AdminLedgerDtoTest {

	@Test
	@DisplayName("LedgerFilter normalizes blank and whitespace strings to null")
	void ledgerFilterSanitizesStrings() {
		Instant now = Instant.now();
		LedgerFilter filter = new LedgerFilter("  owner-1  ", "   ", "\t\n", now, now);
		assertThat(filter.ownerId()).isEqualTo("owner-1");
		assertThat(filter.provider()).isNull();
		assertThat(filter.model()).isNull();
		assertThat(filter.from()).isEqualTo(now);
		assertThat(filter.to()).isEqualTo(now);

		LedgerFilter allNull = new LedgerFilter(null, null, null, null, null);
		assertThat(allNull.ownerId()).isNull();
		assertThat(allNull.provider()).isNull();
		assertThat(allNull.model()).isNull();

		LedgerFilter mixed = new LedgerFilter("owner", "provider", "model", null, null);
		assertThat(mixed.ownerId()).isEqualTo("owner");
		assertThat(mixed.provider()).isEqualTo("provider");
		assertThat(mixed.model()).isEqualTo("model");

		LedgerFilter blankCombinations = new LedgerFilter("", "   ", "\t", null, null);
		assertThat(blankCombinations.ownerId()).isNull();
		assertThat(blankCombinations.provider()).isNull();
		assertThat(blankCombinations.model()).isNull();

		LedgerFilter partialNull = new LedgerFilter(null, "prov", null, null, null);
		assertThat(partialNull.ownerId()).isNull();
		assertThat(partialNull.provider()).isEqualTo("prov");
		assertThat(partialNull.model()).isNull();
	}

	@Test
	@DisplayName("OwnerUsageSummary preserves fields")
	void ownerUsageSummaryRecord() {
		OwnerUsageSummary summary = new OwnerUsageSummary(
				"owner-1", 10L, 1000L, 500L, 1500L, 14_000L,
				BigDecimal.valueOf(0.014), 120.5
		);
		assertThat(summary.ownerId()).isEqualTo("owner-1");
		assertThat(summary.totalRequests()).isEqualTo(10L);
		assertThat(summary.totalPromptTokens()).isEqualTo(1000L);
		assertThat(summary.totalCompletionTokens()).isEqualTo(500L);
		assertThat(summary.totalTokens()).isEqualTo(1500L);
		assertThat(summary.totalCostUsdMicros()).isEqualTo(14_000L);
		assertThat(summary.totalCostUsd()).isEqualTo(BigDecimal.valueOf(0.014));
		assertThat(summary.averageDurationMs()).isEqualTo(120.5);
	}

	@Test
	@DisplayName("ModelUsageSummary preserves fields")
	void modelUsageSummaryRecord() {
		ModelUsageSummary summary = new ModelUsageSummary(
				"openai", "gpt-4o", 5L, 500L, 250L, 750L, 7_000L,
				BigDecimal.valueOf(0.007), 90.0
		);
		assertThat(summary.provider()).isEqualTo("openai");
		assertThat(summary.model()).isEqualTo("gpt-4o");
		assertThat(summary.totalRequests()).isEqualTo(5L);
		assertThat(summary.totalTokens()).isEqualTo(750L);
		assertThat(summary.totalCostUsdMicros()).isEqualTo(7_000L);
		assertThat(summary.totalCostUsd()).isEqualTo(BigDecimal.valueOf(0.007));
	}

	@Test
	@DisplayName("ProviderUsageSummary preserves fields")
	void providerUsageSummaryRecord() {
		ProviderUsageSummary summary = new ProviderUsageSummary(
				"anthropic", 8L, 800L, 400L, 1200L, 11_000L,
				BigDecimal.valueOf(0.011), 150.0
		);
		assertThat(summary.provider()).isEqualTo("anthropic");
		assertThat(summary.totalRequests()).isEqualTo(8L);
		assertThat(summary.totalTokens()).isEqualTo(1200L);
		assertThat(summary.totalCostUsdMicros()).isEqualTo(11_000L);
	}

	@Test
	@DisplayName("LedgerSummaryResponse preserves root and breakdown records")
	void ledgerSummaryResponseRecord() {
		OwnerUsageSummary owner = new OwnerUsageSummary(
				"owner-1",
				1L,
				100L,
				50L,
				150L,
				1400L,
				BigDecimal.valueOf(0.0014),
				80.0
		);
		ModelUsageSummary model = new ModelUsageSummary(
				"openai",
				"gpt-4o",
				1L,
				100L,
				50L,
				150L,
				1400L,
				BigDecimal.valueOf(0.0014),
				80.0
		);
		ProviderUsageSummary provider = new ProviderUsageSummary(
				"openai",
				1L,
				100L,
				50L,
				150L,
				1400L,
				BigDecimal.valueOf(0.0014),
				80.0
		);

		LedgerSummaryResponse response = new LedgerSummaryResponse(
				1L, 100L, 50L, 150L, 1400L, BigDecimal.valueOf(0.0014), 80.0,
				List.of(owner), List.of(model), List.of(provider)
		);

		assertThat(response.totalRequests()).isEqualTo(1L);
		assertThat(response.breakdownByOwner()).containsExactly(owner);
		assertThat(response.breakdownByModel()).containsExactly(model);
		assertThat(response.breakdownByProvider()).containsExactly(provider);
	}

	@Test
	@DisplayName("LedgerEntryResponse preserves all transaction coordinates")
	void ledgerEntryResponseRecord() {
		UUID id = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.now();

		LedgerEntryResponse response = new LedgerEntryResponse(
				id, requestId, "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, BigDecimal.valueOf(0.0014), 250L, now
		);

		assertThat(response.id()).isEqualTo(id);
		assertThat(response.requestId()).isEqualTo(requestId);
		assertThat(response.ownerId()).isEqualTo("owner-1");
		assertThat(response.provider()).isEqualTo("openai");
		assertThat(response.model()).isEqualTo("gpt-4o");
		assertThat(response.promptTokens()).isEqualTo(100);
		assertThat(response.completionTokens()).isEqualTo(50);
		assertThat(response.totalTokens()).isEqualTo(150);
		assertThat(response.costUsdMicros()).isEqualTo(1400L);
		assertThat(response.costUsd()).isEqualTo(BigDecimal.valueOf(0.0014));
		assertThat(response.durationMs()).isEqualTo(250L);
		assertThat(response.createdAt()).isEqualTo(now);
	}

	@Test
	@DisplayName("PageResponse preserves pagination metadata")
	void pageResponseRecord() {
		PageResponse<String> page = new PageResponse<>(List.of("a", "b"), 0, 2, 10L, 5, true);
		assertThat(page.content()).containsExactly("a", "b");
		assertThat(page.page()).isEqualTo(0);
		assertThat(page.size()).isEqualTo(2);
		assertThat(page.totalElements()).isEqualTo(10L);
		assertThat(page.totalPages()).isEqualTo(5);
		assertThat(page.hasNext()).isTrue();
	}
}
