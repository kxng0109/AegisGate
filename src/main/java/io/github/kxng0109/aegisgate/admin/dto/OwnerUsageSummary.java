package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Usage and cost aggregated for a specific tenant or owner.
 *
 * @param ownerId               tenant or owner identifier
 * @param totalRequests         total number of completed requests
 * @param totalPromptTokens     total input tokens consumed
 * @param totalCompletionTokens total output tokens generated
 * @param totalTokens           sum of prompt and completion tokens
 * @param totalCostUsdMicros    total cost in micro-dollars (10^-6 USD)
 * @param totalCostUsd          total cost in USD formatted with exact decimal precision
 * @param averageDurationMs     average request stream duration in milliseconds
 */
public record OwnerUsageSummary(
		String ownerId,
		long totalRequests,
		long totalPromptTokens,
		long totalCompletionTokens,
		long totalTokens,
		long totalCostUsdMicros,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,
		double averageDurationMs
) {
}
