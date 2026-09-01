package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Custom aggregation and dynamic filtering queries for the usage ledger.
 */
public interface UsageLedgerRepositoryCustom {

	/**
	 * Computes global or tenant-scoped totals for requests matching the filter.
	 *
	 * @param filter active filter criteria
	 * @return aggregated totals
	 */
	UsageTotals getTotals(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by tenant or owner.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by ownerId
	 */
	List<OwnerUsageRecord> getBreakdownByOwner(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by provider and model.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by provider and model
	 */
	List<ModelUsageRecord> getBreakdownByModel(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by provider.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by provider
	 */
	List<ProviderUsageRecord> getBreakdownByProvider(LedgerFilter filter);

	/**
	 * Searches ledger entries with dynamic filters and pagination.
	 *
	 * @param filter   active filter criteria
	 * @param pageable pagination parameters
	 * @return paginated entries
	 */
	Page<UsageLedgerEntry> findEntries(LedgerFilter filter, Pageable pageable);
}
