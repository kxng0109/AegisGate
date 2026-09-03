package io.github.kxng0109.aegisgate.security.guardrail.pii;

import io.github.kxng0109.aegisgate.security.guardrail.common.LuhnValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance PII scanner scanning both global enterprise patterns and Nigerian regulatory entities with
 * multi-signal corroboration.
 */
@Component
public class PiiScanner {

	private static final Pattern US_SSN_PATTERN = Pattern.compile(
			"\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"
	);

	private static final Pattern EMAIL_PATTERN = Pattern.compile(
			"\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}\\b"
	);

	private static final Pattern NIGERIAN_E164_PHONE = Pattern.compile(
			"(?<!\\d)(?:\\+234|009234)(?:(?:70[1-9]|80[1-9]|81\\d|90[1-9]|91[1-6])[\\s\\-]?\\d{3}[\\s\\-]?\\d{4}|20[129][\\s\\-]?\\d{3}[\\s\\-]?\\d{4}|20[3-8]\\d[\\s\\-]?\\d{3}[\\s\\-]?\\d{3})(?!\\d)"
	);

	private static final Pattern GENERIC_E164_PHONE = Pattern.compile(
			"(?<!\\d)\\+[1-9]\\d{1,14}(?!\\d)"
	);

	private static final Pattern IBAN_CANDIDATE = Pattern.compile(
			"\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\b"
	);

	private static final Pattern FIRS_TIN_PATTERN = Pattern.compile(
			"\\b\\d{8}-\\d{4}\\b"
	);

	private static final Pattern ELEVEN_DIGIT_CANDIDATE = Pattern.compile(
			"(?<!\\d)(\\d{11})(?!\\d)"
	);

	private static final Pattern CARD_CANDIDATE = Pattern.compile(
			"(?<!\\d)(\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?(?:\\d{4}|\\d{6}|\\d{7}))(?!\\d)"
	);

	private static final Pattern PERSON_HONORIFIC_NAME = Pattern.compile(
			"\\b(?:Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?|Engr\\.?|Chief|Alhaji|Alhaja|Pastor)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\b"
	);

	/**
	 * Scans text and identifies all verified PII entities.
	 *
	 * @param text input prompt text
	 * @return list of verified PII entities sorted by offset
	 */
	public List<PiiEntity> scan(String text) {
		List<PiiEntity> entities = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return entities;
		}

		// 1. US SSN
		Matcher ssnMatcher = US_SSN_PATTERN.matcher(text);
		while (ssnMatcher.find()) {
			entities.add(new PiiEntity(
					PiiType.US_SSN,
					ssnMatcher.group(),
					null,
					ssnMatcher.start(),
					ssnMatcher.end(),
					1.0
			));
		}

		// 2. Email Address
		Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
		while (emailMatcher.find()) {
			entities.add(new PiiEntity(
					PiiType.EMAIL,
					emailMatcher.group(),
					null,
					emailMatcher.start(),
					emailMatcher.end(),
					1.0
			));
		}

		// 3. Nigerian E.164 Phone
		Matcher ngPhoneMatcher = NIGERIAN_E164_PHONE.matcher(text);
		while (ngPhoneMatcher.find()) {
			entities.add(new PiiEntity(
					PiiType.PHONE_E164,
					ngPhoneMatcher.group(),
					null,
					ngPhoneMatcher.start(),
					ngPhoneMatcher.end(),
					0.98
			));
		}

		// 4. Generic E.164 Phone
		Matcher genericPhoneMatcher = GENERIC_E164_PHONE.matcher(text);
		while (genericPhoneMatcher.find()) {
			int start = genericPhoneMatcher.start();
			int end = genericPhoneMatcher.end();
			if (!isOverlapping(entities, start, end)) {
				entities.add(new PiiEntity(
						PiiType.PHONE_E164,
						genericPhoneMatcher.group(),
						null,
						start,
						end,
						0.90
				));
			}
		}

		// 5. IBAN (with ISO 7064 Mod-97-10 check)
		Matcher ibanMatcher = IBAN_CANDIDATE.matcher(text);
		while (ibanMatcher.find()) {
			String candidate = ibanMatcher.group();
			if (IbanValidator.isValid(candidate)) {
				entities.add(new PiiEntity(
						PiiType.IBAN,
						candidate,
						null,
						ibanMatcher.start(),
						ibanMatcher.end(),
						1.0
				));
			}
		}

		// 6. Payment Cards (Verve, Visa, Mastercard)
		Matcher cardMatcher = CARD_CANDIDATE.matcher(text);
		while (cardMatcher.find()) {
			String rawCard = cardMatcher.group(1);
			String digitsOnly = rawCard.replaceAll("[\\s\\-]", "");
			if (LuhnValidator.isValid(digitsOnly)) {
				PiiType cardType = isVerve(digitsOnly) ? PiiType.VERVE_CARD : PiiType.CREDIT_CARD;
				entities.add(new PiiEntity(
						cardType,
						rawCard,
						null,
						cardMatcher.start(),
						cardMatcher.end(),
						1.0
				));
			}
		}

		// 7. Nigerian FIRS TIN (12-digit format: 8 digits - 4 digits)
		Matcher firsMatcher = FIRS_TIN_PATTERN.matcher(text);
		while (firsMatcher.find()) {
			entities.add(new PiiEntity(
					PiiType.NIGERIAN_TIN,
					firsMatcher.group(),
					null,
					firsMatcher.start(),
					firsMatcher.end(),
					0.95
			));
		}

		// 8. 11-Digit Disambiguation (Phone vs BVN vs NIN)
		Matcher elevenDigitMatcher = ELEVEN_DIGIT_CANDIDATE.matcher(text);
		while (elevenDigitMatcher.find()) {
			int start = elevenDigitMatcher.start(1);
			int end = elevenDigitMatcher.end(1);
			if (!isOverlapping(entities, start, end)) {
				String candidate = elevenDigitMatcher.group(1);
				PiiDisambiguationEngine.disambiguate(candidate, text, start)
				                       .ifPresent(res -> entities.add(new PiiEntity(
						                       res.type(),
						                       candidate,
						                       null,
						                       start,
						                       end,
						                       res.confidence()
				                       )));
			}
		}

		// 9. Honorific Names
		Matcher nameMatcher = PERSON_HONORIFIC_NAME.matcher(text);
		while (nameMatcher.find()) {
			int start = nameMatcher.start(1);
			int end = nameMatcher.end(1);
			if (!isOverlapping(entities, start, end)) {
				entities.add(new PiiEntity(
						PiiType.PERSON_NAME,
						nameMatcher.group(1),
						null,
						start,
						end,
						0.88
				));
			}
		}

		// Sort by offset ascending
		entities.sort(Comparator.comparingInt(PiiEntity::startOffset));
		return entities;
	}

	private boolean isOverlapping(List<PiiEntity> entities, int start, int end) {
		for (PiiEntity e : entities) {
			if (Math.max(start, e.startOffset()) < Math.min(end, e.endOffset())) {
				return true;
			}
		}
		return false;
	}

	private boolean isVerve(String digits) {
		if (digits.length() == 16 || digits.length() == 18 || digits.length() == 19) {
			if (digits.startsWith("5060") || digits.startsWith("5061")
					|| digits.startsWith("5078") || digits.startsWith("5079")
					|| digits.startsWith("6500")) {
				return true;
			}
		}
		return false;
	}
}
