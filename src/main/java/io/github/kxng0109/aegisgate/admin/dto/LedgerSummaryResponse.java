package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "LedgerSummaryResponse", description = "Aggregated usage, token, and USD cost summary with multi-dimensional breakdowns")
public record LedgerSummaryResponse(
		@Schema(description = "Total number of completed requests", example = "1500")
		long totalRequests,

		@Schema(description = "Total prompt tokens consumed", example = "750000")
		long totalPromptTokens,

		@Schema(description = "Total completion tokens generated", example = "250000")
		long totalCompletionTokens,

		@Schema(description = "Total combined tokens", example = "1000000")
		long totalTokens,

		@Schema(description = "Total financial cost in micro-dollars (10^-6 USD)", example = "1500000")
		long totalCostUsdMicros,

		@Schema(description = "Total financial cost in USD formatted with 6 decimal places", example = "1.500000")
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,

		@Schema(description = "Average request duration in milliseconds", example = "142.5")
		double averageDurationMs,

		@Schema(description = "Breakdown aggregated by tenant owner ID")
		List<OwnerUsageSummary> breakdownByOwner,

		@Schema(description = "Breakdown aggregated by model identifier")
		List<ModelUsageSummary> breakdownByModel,

		@Schema(description = "Breakdown aggregated by upstream provider")
		List<ProviderUsageSummary> breakdownByProvider
) {
}
