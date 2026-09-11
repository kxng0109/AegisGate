package io.github.kxng0109.aegisgate.admin.dto;

import io.github.kxng0109.aegisgate.budget.BudgetService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Live balance: durable limits plus current window spend. Missing counters read as zero spend.
 */
@Schema(name = "BudgetBalanceResponse", description = "Live spend balance for one budget subject")
public record BudgetBalanceResponse(
		@Schema(description = "Budget level") String level,
		@Schema(description = "Budget subject") String subject,
		@Schema(description = "Rolling-60s cap in micro-dollars") long minuteLimitMicros,
		@Schema(description = "Spent in the current minute window") long minuteSpentMicros,
		@Schema(description = "UTC-month cap in micro-dollars") long monthLimitMicros,
		@Schema(description = "Spent in the current UTC month") long monthSpentMicros
) {
	public static BudgetBalanceResponse from(BudgetService.BalanceView view) {
		return new BudgetBalanceResponse(
				view.level(), view.subject(),
				view.minuteLimitMicros(), view.minuteSpentMicros(),
				view.monthLimitMicros(), view.monthSpentMicros()
		);
	}
}
