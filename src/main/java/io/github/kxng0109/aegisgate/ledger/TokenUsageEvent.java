package io.github.kxng0109.aegisgate.ledger;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the usage and cost of one completed streaming request from the hot path to the asynchronous ledger,
 * incorporating FOCUS 1.4 FinOps taxonomy, prompt caching metrics, and reasoning token telemetry.
 *
 * @param requestId            correlation id of the proxied request
 * @param ownerId              owner of the virtual API key that authenticated it
 * @param provider             name of the provider that served the request
 * @param model                model id reported by the provider
 * @param promptTokens         total input tokens billed by the provider
 * @param completionTokens     output tokens billed by the provider
 * @param totalTokens          the two token counts summed
 * @param durationMs           wall clock time of the stream in milliseconds
 * @param costUsdMicros        list cost in micro dollars, computed from the pricing table
 * @param timestamp            when the request completed
 * @param uncachedPromptTokens input tokens not served from cache
 * @param cacheReadTokens      input tokens served from cache (discounted)
 * @param cacheWriteTokens     input tokens written to cache (surcharged or standard)
 * @param reasoningTokens      reasoning / thinking tokens emitted during generation
 * @param effectiveCostMicros  effective cost taking contracted discounts into account
 * @param billedCostMicros     final billed cost after cache discounts and surcharges
 * @param requestHash          optional cryptographic HMAC request fingerprint for Merkle audit integrity
 */
public record TokenUsageEvent(
		UUID requestId,
		@Nullable String ownerId,
		String provider,
		String model,
		long promptTokens,
		long completionTokens,
		long totalTokens,
		long durationMs,
		long costUsdMicros,
		Instant timestamp,
		long uncachedPromptTokens,
		long cacheReadTokens,
		long cacheWriteTokens,
		long reasoningTokens,
		long effectiveCostMicros,
		long billedCostMicros,
		@Nullable String requestHash
) {

	/**
	 * Convenience constructor maintaining backwards compatibility with standard 10-argument usage events.
	 *
	 * @param requestId        correlation id of the proxied request
	 * @param ownerId          owner of the virtual API key that authenticated it
	 * @param provider         name of the provider that served the request
	 * @param model            model id reported by the provider
	 * @param promptTokens     input tokens billed by the provider
	 * @param completionTokens output tokens billed by the provider
	 * @param totalTokens      the two token counts summed
	 * @param durationMs       wall clock time of the stream in milliseconds
	 * @param costUsdMicros    cost in micro dollars
	 * @param timestamp        when the request completed
	 */
	public TokenUsageEvent(
			UUID requestId,
			@Nullable String ownerId,
			String provider,
			String model,
			long promptTokens,
			long completionTokens,
			long totalTokens,
			long durationMs,
			long costUsdMicros,
			Instant timestamp
	) {
		this(
				requestId,
				ownerId,
				provider,
				model,
				promptTokens,
				completionTokens,
				totalTokens,
				durationMs,
				costUsdMicros,
				timestamp,
				promptTokens,
				0L,
				0L,
				0L,
				costUsdMicros,
				costUsdMicros,
				null
		);
	}
}