package io.github.kxng0109.aegisgate.security.guardrail.secret;

/**
 * Result of an ingress secret/credential leakage scan.
 *
 * @param detected         whether a secret was detected
 * @param ruleId           identifier of the matched rule
 * @param description      human-readable description
 * @param maskedToken      redacted token showing only prefix/anchor
 * @param tokenFingerprint SHA-256 non-reversible fingerprint of the token
 * @param jsonPath         JSON pointer path if matched inside a JSON payload (e.g., "/messages/0/content")
 */
public record SecretScanResult(
		boolean detected,
		String ruleId,
		String description,
		String maskedToken,
		String tokenFingerprint,
		String jsonPath
) {
	public static SecretScanResult clean() {
		return new SecretScanResult(false, null, null, null, null, null);
	}
}
