package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Usage and cost aggregated for a specific upstream provider.
 *
 * @param provider              upstream provider name
 * @param totalRequests         total number of completed requests
 * @param totalPromptTokens     total input tokens consumed
 * @param totalCompletionTokens total output tokens generated
 * @param totalTokens           sum of prompt and completion tokens
 * @param totalCostUsdMicros    total cost in micro-dollars (10^-6 USD)
 * @param totalCostUsd          total cost in USD formatted with exact decimal precision
 * @param averageDurationMs     average request stream duration in milliseconds
 */
@Schema(name = "ProviderUsageSummary", description = "Aggregated usage metrics for a specific upstream provider")
public record ProviderUsageSummary(
		@Schema(description = "Provider identifier", example = "anthropic")
		String provider,

		@Schema(description = "Total number of completed requests", example = "700")
		long totalRequests,

		@Schema(description = "Total prompt tokens consumed", example = "350000")
		long totalPromptTokens,

		@Schema(description = "Total completion tokens generated", example = "130000")
		long totalCompletionTokens,

		@Schema(description = "Total combined tokens", example = "480000")
		long totalTokens,

		@Schema(description = "Total cost in micro-dollars (10^-6 USD)", example = "720000")
		long totalCostUsdMicros,

		@Schema(description = "Total cost in USD formatted with 6 decimal places", example = "0.720000")
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,

		@Schema(description = "Average request duration in milliseconds", example = "150.8")
		double averageDurationMs
) {
}
