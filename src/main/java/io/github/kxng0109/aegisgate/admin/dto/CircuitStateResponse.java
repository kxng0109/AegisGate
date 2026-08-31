package io.github.kxng0109.aegisgate.admin.dto;

/**
 * Representation of an upstream provider circuit breaker state.
 *
 * @param provider provider name
 * @param state    current state (CLOSED, OPEN, HALF_OPEN)
 */
public record CircuitStateResponse(
		String provider,
		String state
) {
}
