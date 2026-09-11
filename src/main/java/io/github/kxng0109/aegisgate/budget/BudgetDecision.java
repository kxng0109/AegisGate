package io.github.kxng0109.aegisgate.budget;

/**
 * Outcome of one atomic spend-budget evaluation. Either every applicable cap admits the estimated spend, or the first
 * violated dimension (KEY, then TEAM, then ORG; minute before month) denies it with a retry horizon.
 */
public sealed interface BudgetDecision {

	/**
	 * Admitted: every applicable cap holds the estimated spend.
	 *
	 * @param remainingMicros lowest (limit - spend) across evaluated dimensions; -1 when nothing constrains
	 * @param resetSeconds    horizon of the binding dimension (minute TTL, or month-end for monthly caps)
	 */
	record Allowed(long remainingMicros, long resetSeconds) implements BudgetDecision {
	}

	/**
	 * Denied: the estimated spend would breach a cap. The denied request consumed nothing (check-before-increment).
	 *
	 * @param level             {@code KEY}, {@code TEAM}, or {@code ORG}
	 * @param window            {@code MINUTE} or {@code MONTH}
	 * @param retryAfterSeconds seconds until the binding window may admit again
	 */
	record Denied(String level, String window, long retryAfterSeconds) implements BudgetDecision {
	}
}
