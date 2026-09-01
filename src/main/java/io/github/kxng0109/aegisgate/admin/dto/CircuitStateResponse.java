package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representation of an upstream provider circuit breaker state.
 *
 * @param provider provider name
 * @param state    current state (CLOSED, OPEN, HALF_OPEN)
 */
@Schema(name = "CircuitStateResponse", description = "Current operational status of an upstream provider circuit breaker")
public record CircuitStateResponse(
		@Schema(description = "Provider identifier", example = "openai")
		String provider,

		@Schema(description = "Circuit state: CLOSED (healthy), OPEN (tripped), or HALF_OPEN (probing)", example = "CLOSED")
		String state
) {
}
