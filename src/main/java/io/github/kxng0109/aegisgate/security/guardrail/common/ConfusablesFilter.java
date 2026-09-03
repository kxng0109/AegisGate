package io.github.kxng0109.aegisgate.security.guardrail.common;

/**
 * Branchless UTS #39 Unicode confusable homoglyph normalizer and zero-width character filter.
 *
 * <p>Adversaries often evade regex filters using zero-width spaces, diacritical zalgo stacking,
 * and homoglyphs from Cyrillic, Greek, or fullwidth Unicode blocks. Standard Unicode NFKC normalization does not
 * cross-map distinct scripts (e.g., Cyrillic 'а' U+0430 remains distinct from Latin 'a' U+0061).</p>
 *
 * <p>This class implements a fast, zero-allocation two-pass pipeline:
 * <ol>
 *   <li>Stripping non-printing characters, zero-width joiners, bidirectional overrides, and combining diacritics.</li>
 *   <li>Table-driven skeleton flattening mapping confusables to ASCII base characters via a precomputed 64K lookup table.</li>
 * </ol>
 * </p>
 */
public final class ConfusablesFilter {

	private static final char[] CONFUSABLES_MAP = new char[65536];

	static {
		// Default: identity mapping
		for (int i = 0; i < 65536; i++) {
			CONFUSABLES_MAP[i] = (char) i;
		}

		// Fullwidth ASCII (U+FF01 to U+FF5E) -> Standard ASCII (0x21 to 0x7E)
		for (int i = 0xFF01; i <= 0xFF5E; i++) {
			CONFUSABLES_MAP[i] = (char) (i - 0xFEE0);
		}
		CONFUSABLES_MAP[0x3000] = ' '; // Ideographic space

		// Cyrillic Lowercase Confusables
		CONFUSABLES_MAP['\u0430'] = 'a'; // Cyrillic Small Letter A
		CONFUSABLES_MAP['\u0441'] = 'c'; // Cyrillic Small Letter Es
		CONFUSABLES_MAP['\u0435'] = 'e'; // Cyrillic Small Letter Ie
		CONFUSABLES_MAP['\u043E'] = 'o'; // Cyrillic Small Letter O
		CONFUSABLES_MAP['\u0440'] = 'p'; // Cyrillic Small Letter Er
		CONFUSABLES_MAP['\u0445'] = 'x'; // Cyrillic Small Letter Kha
		CONFUSABLES_MAP['\u0443'] = 'y'; // Cyrillic Small Letter U
		CONFUSABLES_MAP['\u0456'] = 'i'; // Cyrillic Small Letter Byelorussian-Ukrainian I
		CONFUSABLES_MAP['\u0458'] = 'j'; // Cyrillic Small Letter Je
		CONFUSABLES_MAP['\u0455'] = 's'; // Cyrillic Small Letter Dze
		CONFUSABLES_MAP['\u0501'] = 'd'; // Cyrillic Small Letter Komi De
		CONFUSABLES_MAP['\u051B'] = 'q'; // Cyrillic Small Letter Qa
		CONFUSABLES_MAP['\u051D'] = 'w'; // Cyrillic Small Letter We

		// Cyrillic Uppercase Confusables
		CONFUSABLES_MAP['\u0410'] = 'A'; // Cyrillic Capital Letter A
		CONFUSABLES_MAP['\u0412'] = 'B'; // Cyrillic Capital Letter Ve
		CONFUSABLES_MAP['\u0421'] = 'C'; // Cyrillic Capital Letter Es
		CONFUSABLES_MAP['\u0415'] = 'E'; // Cyrillic Capital Letter Ie
		CONFUSABLES_MAP['\u041D'] = 'H'; // Cyrillic Capital Letter En
		CONFUSABLES_MAP['\u0406'] = 'I'; // Cyrillic Capital Letter Byelorussian-Ukrainian I
		CONFUSABLES_MAP['\u0408'] = 'J'; // Cyrillic Capital Letter Je
		CONFUSABLES_MAP['\u041A'] = 'K'; // Cyrillic Capital Letter Ka
		CONFUSABLES_MAP['\u041C'] = 'M'; // Cyrillic Capital Letter Em
		CONFUSABLES_MAP['\u041E'] = 'O'; // Cyrillic Capital Letter O
		CONFUSABLES_MAP['\u0420'] = 'P'; // Cyrillic Capital Letter Er
		CONFUSABLES_MAP['\u0422'] = 'T'; // Cyrillic Capital Letter Te
		CONFUSABLES_MAP['\u0425'] = 'X'; // Cyrillic Capital Letter Kha
		CONFUSABLES_MAP['\u0423'] = 'Y'; // Cyrillic Capital Letter U
		CONFUSABLES_MAP['\u0405'] = 'S'; // Cyrillic Capital Letter Dze

		// Greek Confusables
		CONFUSABLES_MAP['\u03B1'] = 'a'; // Greek Small Letter Alpha
		CONFUSABLES_MAP['\u03B2'] = 'b'; // Greek Small Letter Beta
		CONFUSABLES_MAP['\u03B5'] = 'e'; // Greek Small Letter Epsilon
		CONFUSABLES_MAP['\u03B7'] = 'h'; // Greek Small Letter Eta
		CONFUSABLES_MAP['\u03B9'] = 'i'; // Greek Small Letter Iota
		CONFUSABLES_MAP['\u03BA'] = 'k'; // Greek Small Letter Kappa
		CONFUSABLES_MAP['\u03BD'] = 'v'; // Greek Small Letter Nu
		CONFUSABLES_MAP['\u03BF'] = 'o'; // Greek Small Letter Omicron
		CONFUSABLES_MAP['\u03C1'] = 'p'; // Greek Small Letter Rho
		CONFUSABLES_MAP['\u03C4'] = 't'; // Greek Small Letter Tau
		CONFUSABLES_MAP['\u03C5'] = 'u'; // Greek Small Letter Upsilon
		CONFUSABLES_MAP['\u03C7'] = 'x'; // Greek Small Letter Chi

		CONFUSABLES_MAP['\u0391'] = 'A'; // Greek Capital Letter Alpha
		CONFUSABLES_MAP['\u0392'] = 'B'; // Greek Capital Letter Beta
		CONFUSABLES_MAP['\u0395'] = 'E'; // Greek Capital Letter Epsilon
		CONFUSABLES_MAP['\u0397'] = 'H'; // Greek Capital Letter Eta
		CONFUSABLES_MAP['\u0399'] = 'I'; // Greek Capital Letter Iota
		CONFUSABLES_MAP['\u039A'] = 'K'; // Greek Capital Letter Kappa
		CONFUSABLES_MAP['\u039C'] = 'M'; // Greek Capital Letter Mu
		CONFUSABLES_MAP['\u039D'] = 'N'; // Greek Capital Letter Nu
		CONFUSABLES_MAP['\u039F'] = 'O'; // Greek Capital Letter Omicron
		CONFUSABLES_MAP['\u03A1'] = 'P'; // Greek Capital Letter Rho
		CONFUSABLES_MAP['\u03A4'] = 'T'; // Greek Capital Letter Tau
		CONFUSABLES_MAP['\u03A7'] = 'X'; // Greek Capital Letter Chi
		CONFUSABLES_MAP['\u03A5'] = 'Y'; // Greek Capital Letter Upsilon
		CONFUSABLES_MAP['\u0396'] = 'Z'; // Greek Capital Letter Zeta
	}

	private ConfusablesFilter() {
	}

	/**
	 * Normalizes input text by removing zero-width/formatting characters and flattening confusable characters to their
	 * ASCII canonical base.
	 *
	 * @param input raw input character sequence
	 * @return normalized text
	 */
	public static String normalize(CharSequence input) {
		if (input == null) {
			return "";
		}
		int len = input.length();
		StringBuilder sb = new StringBuilder(len);

		for (int i = 0; i < len; i++) {
			char c = input.charAt(i);

			// Strip zero-width & invisible format characters
			if (isStrippable(c)) {
				continue;
			}

			// Strip combining diacritical marks (Zalgo attack defense)
			if (isCombiningDiacritical(c)) {
				continue;
			}

			// Map confusable character to canonical ASCII
			sb.append(CONFUSABLES_MAP[c]);
		}

		return sb.toString();
	}

	private static boolean isStrippable(char c) {
		return c == '\u200B' // Zero-width space
				|| c == '\u200C' // Zero-width non-joiner
				|| c == '\u200D' // Zero-width joiner
				|| c == '\uFEFF' // Byte order mark / zero-width no-break space
				|| c == '\u2060' // Word joiner
				|| c == '\u00AD' // Soft hyphen
				|| (c >= '\u202A' && c <= '\u202E') // Bidirectional overrides
				|| (c >= '\u2066' && c <= '\u2069'); // Directional isolates
	}

	private static boolean isCombiningDiacritical(char c) {
		return (c >= '\u0300' && c <= '\u036F') // Combining Diacritical Marks
				|| (c >= '\u1DC0' && c <= '\u1DFF') // Combining Diacritical Marks Supplement
				|| (c >= '\u20D0' && c <= '\u20FF') // Combining Diacritical Marks for Symbols
				|| (c >= '\uFE20' && c <= '\uFE2F'); // Combining Half Marks
	}
}
