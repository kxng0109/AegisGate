package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * High-level aggregated summary of token consumption, cost, and latency with multi-dimensional breakdowns.
 *
 * @param totalRequests         total number of recorded requests matching the filter
 * @param totalPromptTokens     total prompt tokens consumed
 * @param totalCompletionTokens total completion tokens generated
 * @param totalTokens           total tokens billed
 * @param totalCostUsdMicros    total cost in micro-dollars (10^-6 USD)
 * @param totalCostUsd          total cost in USD formatted with exact decimal precision
 * @param averageDurationMs     average stream duration in milliseconds
 * @param breakdownByOwner      usage aggregated per tenant / owner
 * @param breakdownByModel      usage aggregated per provider and model
 * @param breakdownByProvider   usage aggregated per upstream provider
 */
public record LedgerSummaryResponse(
		long totalRequests,
		long totalPromptTokens,
		long totalCompletionTokens,
		long totalTokens,
		long totalCostUsdMicros,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,
		double averageDurationMs,
		List<OwnerUsageSummary> breakdownByOwner,
		List<ModelUsageSummary> breakdownByModel,
		List<ProviderUsageSummary> breakdownByProvider
) {
}
