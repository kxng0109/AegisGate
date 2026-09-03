package io.github.kxng0109.aegisgate.security.guardrail.pii;

/**
 * Represents an identified PII entity within a prompt or document.
 *
 * @param type           category of PII
 * @param originalValue  cleartext PII string
 * @param surrogateToken assigned synthetic replacement token (e.g., "&lt;EMAIL_1&gt;")
 * @param startOffset    character start position in original text
 * @param endOffset      character end position in original text
 * @param confidence     confidence score [0.0 - 1.0]
 */
public record PiiEntity(
		PiiType type,
		String originalValue,
		String surrogateToken,
		int startOffset,
		int endOffset,
		double confidence
) {
}
