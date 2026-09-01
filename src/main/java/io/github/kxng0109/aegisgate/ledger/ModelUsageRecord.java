package io.github.kxng0109.aegisgate.ledger;

/**
 * Low-level projection record holding usage aggregated per provider and model.
 *
 * @param provider              upstream provider name
 * @param model                 upstream model identifier
 * @param totalRequests         total number of matching requests
 * @param totalPromptTokens     total prompt tokens
 * @param totalCompletionTokens total completion tokens
 * @param totalTokens           total tokens
 * @param totalCostUsdMicros    total cost in micro-dollars
 * @param averageDurationMs     average duration in milliseconds
 */
public record ModelUsageRecord(
		String provider,
		String model,
		long totalRequests,
		long totalPromptTokens,
		long totalCompletionTokens,
		long totalTokens,
		long totalCostUsdMicros,
		double averageDurationMs
) {
}
