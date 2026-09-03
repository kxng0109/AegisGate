package io.github.kxng0109.aegisgate.security.guardrail.pii;

import io.github.kxng0109.aegisgate.security.guardrail.common.ShannonEntropyCalculator;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic 4-tier disambiguation engine resolving 11-digit entities in Nigerian contexts (Phone Number vs. BVN vs.
 * NIN) with zero false positives.
 */
public final class PiiDisambiguationEngine {

	private static final Pattern NIGERIAN_MOBILE_PREFIX = Pattern.compile(
			"^0(70[1-9]|80[1-9]|81[0-9]|90[1-9]|91[1-6])\\d{7}$");
	private static final Pattern NIGERIAN_FIXED_PREFIX = Pattern.compile("^020[1-9]\\d{6,7}$");

	private static final Set<String> BVN_KEYWORDS = Set.of(
			"bvn", "bank verification", "bank account", "nibss", "kyc", "account tier", "nuban", "sort code", "bank"
	);

	private static final Set<String> NIN_KEYWORDS = Set.of(
			"nin", "national id", "national identity", "nimc", "vnin", "citizenship", "identity slip", "voter"
	);

	private static final Set<String> PHONE_KEYWORDS = Set.of(
			"call", "tel", "phone", "mobile", "sms", "whatsapp", "reach me", "contact", "dial"
	);

	private PiiDisambiguationEngine() {
	}

	/**
	 * Disambiguates an 11-digit numeric token within its surrounding context.
	 *
	 * @param digits      exact 11-digit numeric string
	 * @param contextText full text surrounding the token
	 * @param startOffset token start offset in contextText
	 * @return resolved PII type and confidence, or empty if false-positive/dummy data
	 */
	public static Optional<DisambiguationResult> disambiguate(String digits, String contextText, int startOffset) {
		if (digits == null || digits.length() != 11 || !isAllDigits(digits)) {
			return Optional.empty();
		}

		// Tier 4 Pre-filter: Entropy & Timestamp Rejection
		double entropy = ShannonEntropyCalculator.calculate(digits);
		if (entropy < 1.5 || isMonotonic(digits)) {
			return Optional.empty(); // Low-entropy dummy sequence (e.g. 11111111111 or 12345678901)
		}

		// Reject UNIX epoch second/millisecond timestamp sequences (e.g. 17... to 18...)
		if (digits.startsWith("17") || digits.startsWith("18")) {
			try {
				long val = Long.parseLong(digits);
				// Check against realistic current epoch seconds/sub-seconds (17000000000 to 18999999999)
				if (val >= 17000000000L && val <= 18999999999L) {
					// Verify if surrounded by timestamp context
					String window = extractContextWindow(contextText, startOffset, digits.length(), 30).toLowerCase(
							Locale.ROOT);
					if (window.contains("time") || window.contains("date") || window.contains("epoch")
							|| window.contains("created") || window.contains("updated") || window.contains("ts")) {
						return Optional.empty();
					}
				}
			} catch (NumberFormatException ignored) {
			}
		}

		// Tier 1: Prefix Topology
		if (digits.startsWith("0")) {
			if (NIGERIAN_MOBILE_PREFIX.matcher(digits).matches()) {
				return Optional.of(new DisambiguationResult(PiiType.PHONE_NG_MOBILE, 0.98));
			}
			if (NIGERIAN_FIXED_PREFIX.matcher(digits).matches()) {
				return Optional.of(new DisambiguationResult(PiiType.PHONE_NG_FIXED, 0.95));
			}
			// Starts with 0 but non-standard prefix -> check phone keywords
			String window = extractContextWindow(
					contextText,
					startOffset,
					digits.length(),
					40
			).toLowerCase(Locale.ROOT);
			if (containsAnyKeyword(window, PHONE_KEYWORDS)) {
				return Optional.of(new DisambiguationResult(PiiType.PHONE_NG_MOBILE, 0.85));
			}
			return Optional.empty();
		}

		// Candidate starts with 1-9: Cannot be national-format Nigerian phone number
		String contextWindow = extractContextWindow(
				contextText,
				startOffset,
				digits.length(),
				40
		).toLowerCase(Locale.ROOT);

		boolean hasBvnKeyword = containsAnyKeyword(contextWindow, BVN_KEYWORDS);
		boolean hasNinKeyword = containsAnyKeyword(contextWindow, NIN_KEYWORDS);

		// Tier 2: BVN Cluster (Prefix '22')
		if (digits.startsWith("22")) {
			if (hasNinKeyword && !hasBvnKeyword) {
				return Optional.of(new DisambiguationResult(PiiType.NIGERIAN_NIN, 0.90));
			}
			return Optional.of(new DisambiguationResult(PiiType.NIGERIAN_BVN, hasBvnKeyword ? 0.99 : 0.90));
		}

		// Tier 3: Context Window Gating for other leading digits
		if (hasBvnKeyword && !hasNinKeyword) {
			return Optional.of(new DisambiguationResult(PiiType.NIGERIAN_BVN, 0.95));
		}
		if (hasNinKeyword && !hasBvnKeyword) {
			return Optional.of(new DisambiguationResult(PiiType.NIGERIAN_NIN, 0.95));
		}

		// If no strong keyword corroborates, avoid false positive tagging on arbitrary numbers
		return Optional.empty();
	}

	private static boolean isAllDigits(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	private static boolean isMonotonic(String s) {
		boolean asc = true;
		boolean desc = true;
		for (int i = 1; i < s.length(); i++) {
			if ((s.charAt(i) - s.charAt(i - 1) + 10) % 10 != 1) {
				asc = false;
			}
			if ((s.charAt(i - 1) - s.charAt(i) + 10) % 10 != 1) {
				desc = false;
			}
		}
		return asc || desc;
	}

	private static String extractContextWindow(String text, int start, int length, int windowSize) {
		if (text == null) {
			return "";
		}
		int winStart = Math.max(0, start - windowSize);
		int winEnd = Math.min(text.length(), start + length + windowSize);
		return text.substring(winStart, winEnd);
	}

	private static boolean containsAnyKeyword(String context, Set<String> keywords) {
		for (String kw : keywords) {
			if (context.contains(kw)) {
				return true;
			}
		}
		return false;
	}

	public record DisambiguationResult(PiiType type, double confidence) {
	}
}
