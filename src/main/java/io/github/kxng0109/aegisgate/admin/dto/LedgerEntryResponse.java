package io.github.kxng0109.aegisgate.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "LedgerEntryResponse", description = "Transaction audit record with full token, duration, and cost coordinates")
public record LedgerEntryResponse(
		@Schema(description = "Internal ledger entry UUID", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
		UUID id,

		@Schema(description = "Correlated client request UUID", example = "123e4567-e89b-12d3-a456-426614174000")
		UUID requestId,

		@Schema(description = "Owner tenant identifier", example = "tenant-corp")
		String ownerId,

		@Schema(description = "Upstream provider name", example = "openai")
		String provider,

		@Schema(description = "Model identifier", example = "gpt-56-luna")
		String model,

		@Schema(description = "Prompt tokens consumed", example = "28")
		int promptTokens,

		@Schema(description = "Completion tokens generated", example = "34")
		int completionTokens,

		@Schema(description = "Total tokens billed", example = "62")
		int totalTokens,

		@Schema(description = "Cost in micro-dollars (10^-6 USD)", example = "93")
		long costUsdMicros,

		@Schema(description = "Cost in USD formatted with 6 decimal places", example = "0.000093")
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costUsd,

		@Schema(description = "Request stream duration in milliseconds", example = "125")
		long durationMs,

		@Schema(description = "Completion timestamp (ISO-8601)", example = "2026-09-01T12:30:00Z")
		Instant createdAt
) {
}
