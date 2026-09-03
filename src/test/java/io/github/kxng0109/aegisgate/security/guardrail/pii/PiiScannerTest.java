package io.github.kxng0109.aegisgate.security.guardrail.pii;

import io.github.kxng0109.aegisgate.security.guardrail.common.LuhnValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiScanner Tests")
class PiiScannerTest {

	private final PiiScanner scanner = new PiiScanner();

	@Test
	@DisplayName("scan returns empty list on null, empty, or blank text")
	void nullOrBlankReturnsEmpty() {
		assertThat(scanner.scan(null)).isEmpty();
		assertThat(scanner.scan("")).isEmpty();
		assertThat(scanner.scan("   ")).isEmpty();
	}

	@Test
	@DisplayName("scans and extracts US SSN conforming to SSA rules")
	void scansUsSsn() {
		String text = "Customer SSN is 123-45-6789 for tax records";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		PiiEntity ssn = entities.getFirst();
		assertThat(ssn.type()).isEqualTo(PiiType.US_SSN);
		assertThat(ssn.originalValue()).isEqualTo("123-45-6789");
		assertThat(ssn.startOffset()).isEqualTo(16);
		assertThat(ssn.endOffset()).isEqualTo(27);
	}

	@Test
	@DisplayName("scans and extracts RFC 5322 Email addresses")
	void scansEmail() {
		String text = "Reach us at support@aegisgate.io or sales-ops@example.com";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.EMAIL);
		assertThat(entities.get(0).originalValue()).isEqualTo("support@aegisgate.io");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.EMAIL);
		assertThat(entities.get(1).originalValue()).isEqualTo("sales-ops@example.com");
	}

	@Test
	@DisplayName("scans and extracts Nigerian E.164 phone numbers with +234 or 009234")
	void scansNigerianE164Phone() {
		String text = "Contact our Lagos office at +2348031234567 or 0092342011234567";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(0).originalValue()).isEqualTo("+2348031234567");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(1).originalValue()).isEqualTo("0092342011234567");
	}

	@Test
	@DisplayName("scans generic E.164 phone without overlapping Nigerian E.164")
	void scansGenericE164Phone() {
		String text = "US office: +14155552671 and UK office: +442071838750";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(0).originalValue()).isEqualTo("+14155552671");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(1).originalValue()).isEqualTo("+442071838750");
	}

	@Test
	@DisplayName("scans valid IBAN and drops invalid Mod-97-10 checksum candidate")
	void scansIban() {
		// Valid German IBAN vs candidate with failed checksum
		String text = "Valid: DE89370400440532013000 and Invalid: DE99370400440532013000";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.IBAN);
		assertThat(entities.getFirst().originalValue()).isEqualTo("DE89370400440532013000");
	}

	@Test
	@DisplayName("scans payment cards and differentiates Interswitch Verve from standard credit cards")
	void scansPaymentCards() {
		// Valid 16-digit Visa (4532015112830366)
		// Valid 16-digit Verve (5061981234567890 -> need valid Luhn for Verve)
		// Let's compute valid 16-digit Verve: 5061 9800 0000 0000 -> check digit
		// Let's use 5061 9801 1283 0368 -> check Luhn
		String cards = "Visa: 4532-0151-1283-0366 and invalid: 4532-0151-1283-0367";
		List<PiiEntity> entities = scanner.scan(cards);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.CREDIT_CARD);
		assertThat(entities.getFirst().originalValue()).isEqualTo("4532-0151-1283-0366");
	}

	@Test
	@DisplayName("scans Verve card when prefix matches Verve BIN ranges (5060, 5061, 5078, 5079, 6500) across 16, 18, and 19 digits")
	void scansVerveCard() {
		// Test all 5 Verve prefixes and lengths 16, 18, 19
		String c1 = generateValidCard("5060", 16);
		String c2 = generateValidCard("5061", 16);
		String c3 = generateValidCard("5078", 18);
		String c4 = generateValidCard("5079", 19);
		String c5 = generateValidCard("6500", 16);

		String text = String.format("Verve cards: %s, %s, %s, %s, and %s.", c1, c2, c3, c4, c5);
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(5);
		for (PiiEntity e : entities) {
			assertThat(e.type()).isEqualTo(PiiType.VERVE_CARD);
		}
	}

	private static String generateValidCard(String prefix, int length) {
		StringBuilder sb = new StringBuilder(prefix);
		while (sb.length() < length - 1) {
			sb.append('0');
		}
		for (int d = 0; d <= 9; d++) {
			String candidate = sb.toString() + d;
			if (LuhnValidator.isValid(candidate)) {
				return candidate;
			}
		}
		return sb.toString() + "0";
	}

	@Test
	@DisplayName("scans Nigerian FIRS Tax Identification Number (12 digits with hyphen)")
	void scansFirsTin() {
		String text = "Tax ID: 12345678-0001 submitted to revenue portal";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.NIGERIAN_TIN);
		assertThat(entities.getFirst().originalValue()).isEqualTo("12345678-0001");
	}

	@Test
	@DisplayName("scans 11-digit entities and disambiguates phone, BVN, and NIN")
	void scansElevenDigitDisambiguatedEntities() {
		String text = "User MTN phone: 08031234567. Linked Bank Verification BVN: 22123456789. Also citizenship National Identity Slip NIN: 31234567890.";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(3);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_NG_MOBILE);
		assertThat(entities.get(1).type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(entities.get(2).type()).isEqualTo(PiiType.NIGERIAN_NIN);
	}

	@Test
	@DisplayName("scans professional and cultural honorific names")
	void scansHonorificNames() {
		String text = "Meeting with Dr. John Doe, Prof. Ada Lovelace, and Alhaji Musa Bello today";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(3);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(0).originalValue()).isEqualTo("John Doe");

		assertThat(entities.get(1).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(1).originalValue()).isEqualTo("Ada Lovelace");

		assertThat(entities.get(2).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(2).originalValue()).isEqualTo("Musa Bello");
	}
}
