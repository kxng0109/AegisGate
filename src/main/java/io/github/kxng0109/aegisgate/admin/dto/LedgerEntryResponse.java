package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Detailed representation of a single persisted ledger entry.
 *
 * @param id               ledger entry primary key
 * @param requestId        correlation ID of the proxied client request
 * @param ownerId          tenant/owner identifier
 * @param provider         upstream provider that answered the request
 * @param model            upstream model reported
 * @param promptTokens     input tokens billed
 * @param completionTokens output tokens billed
 * @param totalTokens      sum of prompt and completion tokens
 * @param costUsdMicros    cost in micro-dollars
 * @param costUsd          cost formatted in USD
 * @param durationMs       stream duration in milliseconds
 * @param createdAt        timestamp when the request completed
 */
public record LedgerEntryResponse(
		UUID id,
		UUID requestId,
		String ownerId,
		String provider,
		String model,
		int promptTokens,
		int completionTokens,
		int totalTokens,
		long costUsdMicros,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costUsd,
		long durationMs,
		Instant createdAt
) {
}
