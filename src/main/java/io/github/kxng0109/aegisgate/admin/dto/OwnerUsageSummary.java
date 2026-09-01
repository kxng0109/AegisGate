package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "OwnerUsageSummary", description = "Aggregated usage metrics for a specific tenant owner")
public record OwnerUsageSummary(
		@Schema(description = "Owner tenant identifier", example = "tenant-corp")
		String ownerId,

		@Schema(description = "Total number of completed requests", example = "500")
		long totalRequests,

		@Schema(description = "Total prompt tokens consumed", example = "250000")
		long totalPromptTokens,

		@Schema(description = "Total completion tokens generated", example = "75000")
		long totalCompletionTokens,

		@Schema(description = "Total combined tokens", example = "325000")
		long totalTokens,

		@Schema(description = "Total cost in micro-dollars (10^-6 USD)", example = "487500")
		long totalCostUsdMicros,

		@Schema(description = "Total cost in USD formatted with 6 decimal places", example = "0.487500")
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,

		@Schema(description = "Average request duration in milliseconds", example = "128.4")
		double averageDurationMs
) {
}
