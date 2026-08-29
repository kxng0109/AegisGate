package io.github.kxng0109.aegisgate.contracts;

/**
 * Reason a virtual API key request was rejected by the rate-limit / key layer.
 */
public enum RejectionReason {
	RPM_EXCEEDED,
	TPM_EXCEEDED,
	KEY_DISABLED,
	KEY_NOT_FOUND,
	MODEL_NOT_ALLOWED
}
