package io.github.kxng0109.aegisgate.security.guardrail.common;

/**
 * Branchless ISO/IEC 7812 Mod-10 Luhn checksum validator.
 *
 * <p>Validates primary account numbers (payment cards such as Visa, Mastercard, and Verve)
 * using precomputed doubling and parity lookup tables, avoiding runtime branches and modulo operations in inner
 * loops.</p>
 */
public final class LuhnValidator {

	/**
	 * Precomputed LUT for doubling a digit and subtracting 9 if >= 10: {@code (d * 2) >= 10 ? (d * 2 - 9) : (d * 2)}.
	 */
	private static final int[] DOUBLED = {0, 2, 4, 6, 8, 1, 3, 5, 7, 9};

	private LuhnValidator() {
	}

	/**
	 * Validates a character sequence according to the Luhn Mod-10 algorithm. Allows optional whitespace and hyphens
	 * which are skipped.
	 *
	 * @param pan candidate primary account number
	 * @return {@code true} if the sequence contains at least 12 digits and satisfies the Luhn check
	 */
	public static boolean isValid(CharSequence pan) {
		if (pan == null || pan.length() < 12) {
			return false;
		}

		int sum = 0;
		boolean alternate = false;
		int digitCount = 0;

		for (int i = pan.length() - 1; i >= 0; i--) {
			char c = pan.charAt(i);
			if (c == ' ' || c == '-') {
				continue;
			}
			if (c < '0' || c > '9') {
				return false;
			}
			int d = c - '0';
			sum += alternate ? DOUBLED[d] : d;
			alternate = !alternate;
			digitCount++;
		}

		return digitCount >= 12 && (sum % 10 == 0);
	}

	/**
	 * Validates a slice of raw UTF-8 bytes according to the Luhn Mod-10 algorithm.
	 *
	 * @param bytes  input byte buffer
	 * @param offset starting index
	 * @param length length of slice
	 * @return {@code true} if valid
	 */
	public static boolean isValid(byte[] bytes, int offset, int length) {
		if (bytes == null || length < 12 || offset < 0 || offset + length > bytes.length) {
			return false;
		}

		int sum = 0;
		boolean alternate = false;
		int digitCount = 0;

		for (int i = offset + length - 1; i >= offset; i--) {
			byte b = bytes[i];
			if (b == ' ' || b == '-') {
				continue;
			}
			if (b < '0' || b > '9') {
				return false;
			}
			int d = b - '0';
			sum += alternate ? DOUBLED[d] : d;
			alternate = !alternate;
			digitCount++;
		}

		return digitCount >= 12 && (sum % 10 == 0);
	}
}
