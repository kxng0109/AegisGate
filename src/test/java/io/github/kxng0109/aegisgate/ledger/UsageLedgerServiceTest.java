package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.admin.dto.LedgerEntryResponse;
import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import io.github.kxng0109.aegisgate.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.aegisgate.admin.dto.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("UsageLedgerService")
class UsageLedgerServiceTest {

	private final UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
	private final UsageLedgerService service = new UsageLedgerService(repository);

	@Test
	@DisplayName("getSummary aggregates totals and dimensional breakdowns")
	void getSummarySuccess() {
		LedgerFilter filter = new LedgerFilter("owner-1", "openai", "gpt-4o", null, null);

		when(repository.getTotals(filter)).thenReturn(new UsageTotals(10L, 1000L, 500L, 1500L, 14_000L, 120.0));
		when(repository.getBreakdownByOwner(filter)).thenReturn(List.of(
				new OwnerUsageRecord("owner-1", 10L, 1000L, 500L, 1500L, 14_000L, 120.0)
		));
		when(repository.getBreakdownByModel(filter)).thenReturn(List.of(
				new ModelUsageRecord("openai", "gpt-4o", 10L, 1000L, 500L, 1500L, 14_000L, 120.0)
		));
		when(repository.getBreakdownByProvider(filter)).thenReturn(List.of(
				new ProviderUsageRecord("openai", 10L, 1000L, 500L, 1500L, 14_000L, 120.0)
		));

		LedgerSummaryResponse summary = service.getSummary(filter);

		assertThat(summary.totalRequests()).isEqualTo(10L);
		assertThat(summary.totalPromptTokens()).isEqualTo(1000L);
		assertThat(summary.totalCompletionTokens()).isEqualTo(500L);
		assertThat(summary.totalTokens()).isEqualTo(1500L);
		assertThat(summary.totalCostUsdMicros()).isEqualTo(14_000L);
		assertThat(summary.totalCostUsd()).isEqualTo(BigDecimal.valueOf(14000, 6));
		assertThat(summary.averageDurationMs()).isEqualTo(120.0);

		assertThat(summary.breakdownByOwner()).hasSize(1);
		assertThat(summary.breakdownByOwner().getFirst().ownerId()).isEqualTo("owner-1");
		assertThat(summary.breakdownByOwner().getFirst().totalCostUsd()).isEqualTo(BigDecimal.valueOf(14000, 6));

		assertThat(summary.breakdownByModel()).hasSize(1);
		assertThat(summary.breakdownByModel().getFirst().model()).isEqualTo("gpt-4o");

		assertThat(summary.breakdownByProvider()).hasSize(1);
		assertThat(summary.breakdownByProvider().getFirst().provider()).isEqualTo("openai");
	}

	@Test
	@DisplayName("getSummary handles zero matches safely without error")
	void getSummaryZeroMatches() {
		LedgerFilter filter = new LedgerFilter(null, null, null, null, null);

		when(repository.getTotals(filter)).thenReturn(new UsageTotals(0L, 0L, 0L, 0L, 0L, 0.0));
		when(repository.getBreakdownByOwner(filter)).thenReturn(List.of());
		when(repository.getBreakdownByModel(filter)).thenReturn(List.of());
		when(repository.getBreakdownByProvider(filter)).thenReturn(List.of());

		LedgerSummaryResponse summary = service.getSummary(filter);

		assertThat(summary.totalRequests()).isZero();
		assertThat(summary.totalTokens()).isZero();
		assertThat(summary.totalCostUsdMicros()).isZero();
		assertThat(summary.totalCostUsd()).isEqualTo(BigDecimal.valueOf(0, 6));
		assertThat(summary.breakdownByOwner()).isEmpty();
		assertThat(summary.breakdownByModel()).isEmpty();
		assertThat(summary.breakdownByProvider()).isEmpty();
	}

	@Test
	@DisplayName("getSummary throws 400 Bad Request on invalid date ranges")
	void getSummaryDateValidation() {
		Instant now = Instant.now();
		Instant past = now.minus(Duration.ofDays(10));

		// from > to
		LedgerFilter invalidOrder = new LedgerFilter(null, null, null, now, past);
		assertThatThrownBy(() -> service.getSummary(invalidOrder))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Parameter 'from' cannot be after 'to'");

		// window > 90 days
		Instant ninetyFiveDaysAgo = now.minus(Duration.ofDays(95));
		LedgerFilter windowExceeded = new LedgerFilter(null, null, null, ninetyFiveDaysAgo, now);
		assertThatThrownBy(() -> service.getSummary(windowExceeded))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Query window exceeds maximum allowed limit");

		// Single bound filters (from only, or to only) are valid
		when(repository.getTotals(any())).thenReturn(new UsageTotals(0L, 0L, 0L, 0L, 0L, 0.0));
		when(repository.getBreakdownByOwner(any())).thenReturn(List.of());
		when(repository.getBreakdownByModel(any())).thenReturn(List.of());
		when(repository.getBreakdownByProvider(any())).thenReturn(List.of());

		assertThat(service.getSummary(new LedgerFilter(null, null, null, now, null))).isNotNull();
		assertThat(service.getSummary(new LedgerFilter(null, null, null, null, now))).isNotNull();
	}

	@Test
	@DisplayName("getEntries clamps page size and maps to PageResponse")
	void getEntriesPaginationAndClamping() {
		LedgerFilter filter = new LedgerFilter("owner-1", null, null, null, null);
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.now();

		UsageLedgerEntry entry = new UsageLedgerEntry(
				requestId, "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, 200L, now
		);

		when(repository.findEntries(eq(filter), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));

		// Normal request
		PageResponse<LedgerEntryResponse> response = service.getEntries(filter, PageRequest.of(0, 20));
		assertThat(response.content()).hasSize(1);
		assertThat(response.totalElements()).isEqualTo(1L);
		assertThat(response.content().getFirst().requestId()).isEqualTo(requestId);
		assertThat(response.content().getFirst().costUsd()).isEqualTo(BigDecimal.valueOf(1400, 6));

		// Oversized page size is clamped to 100
		service.getEntries(filter, PageRequest.of(0, 500));
		verify(repository).findEntries(eq(filter), argThat(p -> p.getPageSize() == 100));

		// Custom pageable with negative page or zero size
		Pageable customPageable = mock(Pageable.class);
		when(customPageable.isUnpaged()).thenReturn(false);
		when(customPageable.getPageNumber()).thenReturn(-5);
		when(customPageable.getPageSize()).thenReturn(0);
		when(customPageable.getSort()).thenReturn(Sort.unsorted());

		service.getEntries(filter, customPageable);
		verify(repository).findEntries(eq(filter), argThat(p -> p.getPageSize() == 1 && p.getPageNumber() == 0));

		// Unpaged defaults to 20
		service.getEntries(filter, Pageable.unpaged());
		verify(repository, times(2)).findEntries(
				eq(filter),
				argThat(p -> p.getPageSize() == 20 && p.getPageNumber() == 0)
		);
	}

	@Test
	@DisplayName("getEntryByRequestId returns mapped entry or empty")
	void getEntryByRequestIdScenarios() {
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.now();
		UsageLedgerEntry entry = new UsageLedgerEntry(
				requestId, "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, 200L, now
		);

		when(repository.findByRequestId(requestId)).thenReturn(Optional.of(entry));
		when(repository.findByRequestId(argThat(r -> r != null && !r.equals(requestId)))).thenReturn(Optional.empty());

		Optional<LedgerEntryResponse> found = service.getEntryByRequestId(requestId);
		assertThat(found).isPresent();
		assertThat(found.get().requestId()).isEqualTo(requestId);
		assertThat(found.get().ownerId()).isEqualTo("owner-1");

		Optional<LedgerEntryResponse> notFound = service.getEntryByRequestId(UUID.randomUUID());
		assertThat(notFound).isEmpty();
	}

	@Test
	@DisplayName("microsToUsd produces exact 6-scale BigDecimal without binary floating point distortion")
	void microsToUsdExactMath() {
		assertThat(UsageLedgerService.microsToUsd(14_000L)).isEqualTo("0.014000");
		assertThat(UsageLedgerService.microsToUsd(1L)).isEqualTo("0.000001");
		assertThat(UsageLedgerService.microsToUsd(1_000_000L)).isEqualTo("1.000000");
		assertThat(UsageLedgerService.microsToUsd(0L)).isEqualTo("0.000000");
	}
}
