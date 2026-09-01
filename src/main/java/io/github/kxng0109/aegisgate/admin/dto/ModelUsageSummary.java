package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Usage and cost aggregated for a specific provider and model.
 *
 * @param provider              upstream provider name
 * @param model                 upstream model identifier
 * @param totalRequests         total number of completed requests
 * @param totalPromptTokens     total input tokens consumed
 * @param totalCompletionTokens total output tokens generated
 * @param totalTokens           sum of prompt and completion tokens
 * @param totalCostUsdMicros    total cost in micro-dollars (10^-6 USD)
 * @param totalCostUsd          total cost in USD formatted with exact decimal precision
 * @param averageDurationMs     average request stream duration in milliseconds
 */
@Schema(name = "ModelUsageSummary", description = "Aggregated usage metrics for a specific provider and model")
public record ModelUsageSummary(
		@Schema(description = "Provider identifier", example = "openai")
		String provider,

		@Schema(description = "Model identifier", example = "gpt-56-luna")
		String model,

		@Schema(description = "Total number of completed requests", example = "800")
		long totalRequests,

		@Schema(description = "Total prompt tokens consumed", example = "400000")
		long totalPromptTokens,

		@Schema(description = "Total completion tokens generated", example = "120000")
		long totalCompletionTokens,

		@Schema(description = "Total combined tokens", example = "520000")
		long totalTokens,

		@Schema(description = "Total cost in micro-dollars (10^-6 USD)", example = "780000")
		long totalCostUsdMicros,

		@Schema(description = "Total cost in USD formatted with 6 decimal places", example = "0.780000")
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCostUsd,

		@Schema(description = "Average request duration in milliseconds", example = "135.2")
		double averageDurationMs
) {
}
