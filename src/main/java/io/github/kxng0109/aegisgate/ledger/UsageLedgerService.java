package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.admin.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating analytical aggregations, tenant billing summaries, and audit trail queries against the usage
 * ledger.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageLedgerService {

	private static final Duration MAX_QUERY_WINDOW = Duration.ofDays(90);
	private static final int MAX_PAGE_SIZE = 100;
	private static final int DEFAULT_PAGE_SIZE = 20;

	private final UsageLedgerRepository repository;

	/**
	 * Computes an aggregated billing summary including global/filtered totals and dimensional breakdowns.
	 *
	 * @param filter query filter parameters
	 * @return aggregated summary response
	 */
	public LedgerSummaryResponse getSummary(LedgerFilter filter) {
		validateFilter(filter);

		UsageTotals totals = repository.getTotals(filter);
		List<OwnerUsageRecord> ownerRecords = repository.getBreakdownByOwner(filter);
		List<ModelUsageRecord> modelRecords = repository.getBreakdownByModel(filter);
		List<ProviderUsageRecord> providerRecords = repository.getBreakdownByProvider(filter);

		List<OwnerUsageSummary> ownerSummaries = ownerRecords.stream()
		                                                     .map(r -> new OwnerUsageSummary(
				                                                     r.ownerId(),
				                                                     r.totalRequests(),
				                                                     r.totalPromptTokens(),
				                                                     r.totalCompletionTokens(),
				                                                     r.totalTokens(),
				                                                     r.totalCostUsdMicros(),
				                                                     microsToUsd(r.totalCostUsdMicros()),
				                                                     r.averageDurationMs()
		                                                     ))
		                                                     .toList();

		List<ModelUsageSummary> modelSummaries = modelRecords.stream()
		                                                     .map(r -> new ModelUsageSummary(
				                                                     r.provider(),
				                                                     r.model(),
				                                                     r.totalRequests(),
				                                                     r.totalPromptTokens(),
				                                                     r.totalCompletionTokens(),
				                                                     r.totalTokens(),
				                                                     r.totalCostUsdMicros(),
				                                                     microsToUsd(r.totalCostUsdMicros()),
				                                                     r.averageDurationMs()
		                                                     ))
		                                                     .toList();

		List<ProviderUsageSummary> providerSummaries = providerRecords.stream()
		                                                              .map(r -> new ProviderUsageSummary(
				                                                              r.provider(),
				                                                              r.totalRequests(),
				                                                              r.totalPromptTokens(),
				                                                              r.totalCompletionTokens(),
				                                                              r.totalTokens(),
				                                                              r.totalCostUsdMicros(),
				                                                              microsToUsd(r.totalCostUsdMicros()),
				                                                              r.averageDurationMs()
		                                                              ))
		                                                              .toList();

		return new LedgerSummaryResponse(
				totals.totalRequests(),
				totals.totalPromptTokens(),
				totals.totalCompletionTokens(),
				totals.totalTokens(),
				totals.totalCostUsdMicros(),
				microsToUsd(totals.totalCostUsdMicros()),
				totals.averageDurationMs(),
				ownerSummaries,
				modelSummaries,
				providerSummaries
		);
	}

	/**
	 * Retrieves paginated audit entries matching the filter.
	 *
	 * @param filter   query filter parameters
	 * @param pageable requested pagination
	 * @return paginated entries envelope
	 */
	public PageResponse<LedgerEntryResponse> getEntries(LedgerFilter filter, Pageable pageable) {
		validateFilter(filter);
		Pageable clampedPageable = clampPageable(pageable);

		Page<UsageLedgerEntry> page = repository.findEntries(filter, clampedPageable);
		List<LedgerEntryResponse> content = page.getContent().stream()
		                                        .map(this::toEntryResponse)
		                                        .toList();

		return new PageResponse<>(
				content,
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.hasNext()
		);
	}

	/**
	 * Finds a single ledger entry by its request correlation ID.
	 *
	 * @param requestId client request correlation ID
	 * @return entry representation if found
	 */
	public Optional<LedgerEntryResponse> getEntryByRequestId(UUID requestId) {
		return repository.findByRequestId(requestId).map(this::toEntryResponse);
	}

	/**
	 * Converts micro-dollars (10^-6 USD) to a 6-scale {@link BigDecimal} without binary float distortion.
	 *
	 * @param costUsdMicros micro-dollars
	 * @return exact decimal USD value
	 */
	public static BigDecimal microsToUsd(long costUsdMicros) {
		return BigDecimal.valueOf(costUsdMicros, 6);
	}

	private void validateFilter(LedgerFilter filter) {
		if (filter.from() != null && filter.to() != null) {
			if (filter.from().isAfter(filter.to())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'from' cannot be after 'to'");
			}
			Duration duration = Duration.between(filter.from(), filter.to());
			if (duration.compareTo(MAX_QUERY_WINDOW) > 0) {
				throw new ResponseStatusException(
						HttpStatus.BAD_REQUEST,
						"Query window exceeds maximum allowed limit of " + MAX_QUERY_WINDOW.toDays() + " days"
				);
			}
		}
	}

	private Pageable clampPageable(Pageable pageable) {
		if (pageable.isUnpaged()) {
			return PageRequest.of(0, DEFAULT_PAGE_SIZE);
		}
		int pageNumber = Math.max(0, pageable.getPageNumber());
		int pageSize = Math.clamp(pageable.getPageSize(), 1, MAX_PAGE_SIZE);
		return PageRequest.of(pageNumber, pageSize, pageable.getSort());
	}

	private LedgerEntryResponse toEntryResponse(UsageLedgerEntry entry) {
		return new LedgerEntryResponse(
				entry.getId(),
				entry.getRequestId(),
				entry.getOwnerId(),
				entry.getProvider(),
				entry.getModel(),
				entry.getPromptTokens(),
				entry.getCompletionTokens(),
				entry.getTotalTokens(),
				entry.getCostUsdMicros(),
				microsToUsd(entry.getCostUsdMicros()),
				entry.getDurationMs(),
				entry.getCreatedAt()
		);
	}
}
