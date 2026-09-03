package io.github.kxng0109.aegisgate.security.guardrail.pii;

/**
 * ISO 7064 Mod-97-10 checksum validator for International Bank Account Numbers (IBAN). Evaluates checksum piecewise
 * with zero BigInteger heap allocations.
 */
public final class IbanValidator {

	private IbanValidator() {
	}

	/**
	 * Validates an IBAN according to ISO 13616 / ISO 7064 Mod-97-10.
	 *
	 * @param candidate string containing IBAN
	 * @return {@code true} if format and Mod-97-10 checksum are valid
	 */
	public static boolean isValid(CharSequence candidate) {
		if (candidate == null) {
			return false;
		}

		// Strip spaces and hyphens
		StringBuilder clean = new StringBuilder(candidate.length());
		for (int i = 0; i < candidate.length(); i++) {
			char c = candidate.charAt(i);
			if (c != ' ' && c != '-') {
				clean.append(Character.toUpperCase(c));
			}
		}

		int len = clean.length();
		if (len < 15 || len > 34) {
			return false;
		}

		// First two characters must be ISO country code (A-Z)
		if (clean.charAt(0) < 'A' || clean.charAt(0) > 'Z'
				|| clean.charAt(1) < 'A' || clean.charAt(1) > 'Z') {
			return false;
		}

		// Next two characters must be digits (0-9)
		if (clean.charAt(2) < '0' || clean.charAt(2) > '9'
				|| clean.charAt(3) < '0' || clean.charAt(3) > '9') {
			return false;
		}

		// Move first 4 characters to the end: clean[4..len-1] + clean[0..3]
		int remainder = 0;

		// Process clean[4..len-1]
		for (int i = 4; i < len; i++) {
			char c = clean.charAt(i);
			remainder = processChar(remainder, c);
			if (remainder < 0) {
				return false;
			}
		}

		// Process clean[0..3]
		for (int i = 0; i < 4; i++) {
			char c = clean.charAt(i);
			remainder = processChar(remainder, c);
		}

		return remainder == 1;
	}

	private static int processChar(int currentRemainder, char c) {
		if (c >= '0' && c <= '9') {
			return (currentRemainder * 10 + (c - '0')) % 97;
		} else if (c >= 'A' && c <= 'Z') {
			int val = c - 'A' + 10;
			int tens = val / 10;
			int ones = val % 10;
			int rem1 = (currentRemainder * 10 + tens) % 97;
			return (rem1 * 10 + ones) % 97;
		} else {
			return -1; // Invalid character
		}
	}
}
