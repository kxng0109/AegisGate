package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;

/**
 * Payload for creating a hard spend budget.
 *
 * @param level        {@code KEY}, {@code TEAM}, or {@code ORG}
 * @param subjectId    key sha256 hex (KEY) or slug {@code [a-z0-9-]} (TEAM/ORG)
 * @param minuteMicros rolling-60s cap in micro-dollars (0 = no minute cap)
 * @param monthMicros  UTC-calendar-month cap in micro-dollars (0 = no monthly cap)
 * @param webhookUrl   optional alert webhook (http/https, SSRF-validated at write time)
 */
@Schema(name = "CreateBudgetRequest", description = "Payload for creating a hard spend cap")
public record CreateBudgetRequest(
		@Schema(description = "Budget level", example = "TEAM", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "level must not be blank")
		String level,

		@Schema(description = "Budget subject: key hex, team slug, or org scope", example = "tenant-corp",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "subjectId must not be blank")
		String subjectId,

		@Schema(description = "Rolling-60s cap in micro-dollars (0 = none)", example = "5000000", minimum = "0")
		@PositiveOrZero(message = "minuteMicros must be non-negative")
		Long minuteMicros,

		@Schema(description = "UTC-month cap in micro-dollars (0 = none)", example = "200000000", minimum = "0")
		@PositiveOrZero(message = "monthMicros must be non-negative")
		Long monthMicros,

		@Schema(description = "Optional alert webhook URL", example = "https://ops.example.com/hooks/budget")
		@Nullable String webhookUrl
) {
	public CreateBudgetRequest {
		minuteMicros = minuteMicros != null ? minuteMicros : 0L;
		monthMicros = monthMicros != null ? monthMicros : 0L;
	}
}
