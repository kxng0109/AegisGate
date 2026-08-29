package io.github.kxng0109.aegisgate.ledger;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the usage and cost of one completed streaming request from the hot path to the asynchronous ledger.
 *
 * <p>The event is published exactly once, after the stream finished, and is
 * handled on a dedicated executor so the publishing thread never blocks. It carries no secrets: the owner id is the
 * tenant identifier resolved from the virtual API key, and the provider is the winning provider name.</p>
 *
 * @param requestId        correlation id of the proxied request
 * @param ownerId          owner of the virtual API key that authenticated it
 * @param provider         name of the provider that served the request
 * @param model            model id reported by the provider
 * @param promptTokens     input tokens billed by the provider
 * @param completionTokens output tokens billed by the provider
 * @param totalTokens      the two token counts summed
 * @param durationMs       wall clock time of the stream in milliseconds
 * @param costUsdMicros    cost in micro dollars, computed from the pricing table
 * @param timestamp        when the request completed
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
		Instant timestamp
) {
}