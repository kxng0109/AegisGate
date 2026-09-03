package io.github.kxng0109.aegisgate.security.guardrail.common;

/**
 * Operating mode for AegisGate guardrails.
 */
public enum GuardrailMode {
	/**
	 * Actively blocks violating requests (rejects with 422 or terminates streams with error).
	 */
	ENFORCE,

	/**
	 * Allows requests to proceed, recording violations in logs and metrics for auditing.
	 */
	AUDIT_ONLY
}
