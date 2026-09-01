package io.github.kxng0109.aegisgate.ledger;

/**
 * Low-level projection record holding usage aggregated per tenant or owner.
 *
 * @param ownerId               tenant or owner identifier
 * @param totalRequests         total number of matching requests
 * @param totalPromptTokens     total prompt tokens
 * @param totalCompletionTokens total completion tokens
 * @param totalTokens           total tokens
 * @param totalCostUsdMicros    total cost in micro-dollars
 * @param averageDurationMs     average duration in milliseconds
 */
public record OwnerUsageRecord(
		String ownerId,
		long totalRequests,
		long totalPromptTokens,
		long totalCompletionTokens,
		long totalTokens,
		long totalCostUsdMicros,
		double averageDurationMs
) {
}
