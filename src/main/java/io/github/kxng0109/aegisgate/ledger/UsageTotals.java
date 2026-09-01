package io.github.kxng0109.aegisgate.ledger;

/**
 * Low-level projection record holding scalar aggregation totals from database queries.
 *
 * @param totalRequests         total number of matching requests
 * @param totalPromptTokens     total prompt tokens
 * @param totalCompletionTokens total completion tokens
 * @param totalTokens           total tokens
 * @param totalCostUsdMicros    total cost in micro-dollars
 * @param averageDurationMs     average duration in milliseconds
 */
public record UsageTotals(
		long totalRequests,
		long totalPromptTokens,
		long totalCompletionTokens,
		long totalTokens,
		long totalCostUsdMicros,
		double averageDurationMs
) {
}
