package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;

/**
 * Payload for replacing a spend budget's money fields (optimistic locking fails concurrent edits loudly).
 *
 * @param minuteMicros rolling-60s cap in micro-dollars (0 = no minute cap)
 * @param monthMicros  UTC-month cap in micro-dollars (0 = no monthly cap)
 * @param webhookUrl   optional alert webhook URL (null clears it)
 */
@Schema(name = "UpdateBudgetRequest", description = "Payload for replacing a spend budget's caps")
public record UpdateBudgetRequest(
		@Schema(description = "Rolling-60s cap in micro-dollars (0 = none)", example = "5000000", minimum = "0")
		@PositiveOrZero(message = "minuteMicros must be non-negative")
		Long minuteMicros,

		@Schema(description = "UTC-month cap in micro-dollars (0 = none)", example = "200000000", minimum = "0")
		@PositiveOrZero(message = "monthMicros must be non-negative")
		Long monthMicros,

		@Schema(description = "Optional alert webhook URL (null clears it)")
		@Nullable String webhookUrl
) {
	public UpdateBudgetRequest {
		minuteMicros = minuteMicros != null ? minuteMicros : 0L;
		monthMicros = monthMicros != null ? monthMicros : 0L;
	}
}
