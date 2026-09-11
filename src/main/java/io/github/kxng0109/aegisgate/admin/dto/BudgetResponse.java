package io.github.kxng0109.aegisgate.admin.dto;

import io.github.kxng0109.aegisgate.budget.BudgetLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One hard spend budget with its durable limits.
 */
@Schema(name = "BudgetResponse", description = "A hard spend cap")
public record BudgetResponse(
		@Schema(description = "Budget id") UUID id,
		@Schema(description = "KEY, TEAM, or ORG") String level,
		@Schema(description = "Budget subject") String subjectId,
		@Schema(description = "Rolling-60s cap in micro-dollars") long minuteMicros,
		@Schema(description = "UTC-month cap in micro-dollars") long monthMicros,
		@Schema(description = "Alert webhook URL, if any") @Nullable String webhookUrl,
		@Schema(description = "Creation time") Instant createdAt,
		@Schema(description = "Last update time") Instant updatedAt
) {
	public static BudgetResponse from(BudgetLimit limit) {
		return new BudgetResponse(
				limit.getId(), limit.getLevel(), limit.getSubjectId(),
				limit.getMinuteMicros(), limit.getMonthMicros(), limit.getWebhookUrl(),
				limit.getCreatedAt(), limit.getUpdatedAt()
		);
	}
}
