package io.github.kxng0109.aegisgate.security.guardrail.injection;

/**
 * Result of a prompt injection or jailbreak scan.
 *
 * @param detected       whether prompt injection or jailbreak was detected
 * @param category       category of injection (e.g. INSTRUCTION_OVERRIDE, PERSONA_SIMULATION)
 * @param matchedPattern the matched signature or trigger
 * @param riskScore      composite risk score [0.0 - 1.0]
 * @param detail         human-readable explanation
 */
public record InjectionScanResult(
		boolean detected,
		String category,
		String matchedPattern,
		double riskScore,
		String detail
) {
	public static InjectionScanResult clean() {
		return new InjectionScanResult(false, null, null, 0.0, null);
	}
}
