package io.github.kxng0109.aegisgate.security.guardrail.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IbanValidator Tests")
class IbanValidatorTest {

	// Valid German IBAN: DE89 3704 0044 0532 0130 00
	private static final String VALID_IBAN_DE = "DE89370400440532013000";
	// Valid UK IBAN: GB29 NWBK 6016 1331 9268 19
	private static final String VALID_IBAN_GB = "GB29NWBK60161331926819";

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<IbanValidator> constructor = IbanValidator.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		IbanValidator instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("returns false on null or invalid lengths")
	void nullAndLengthChecks() {
		assertThat(IbanValidator.isValid(null)).isFalse();
		assertThat(IbanValidator.isValid("")).isFalse();
		assertThat(IbanValidator.isValid("DE8937040044")).isFalse(); // 12 chars < 15
		assertThat(IbanValidator.isValid("DE89" + "1".repeat(35))).isFalse(); // > 34 chars
	}

	@Test
	@DisplayName("returns false on invalid country code or check digits")
	void invalidCountryCodeOrCheckDigits() {
		// Non-alpha country code (< 'A' and > 'Z')
		assertThat(IbanValidator.isValid("1289370400440532013000")).isFalse();
		assertThat(IbanValidator.isValid("@E89370400440532013000")).isFalse();
		assertThat(IbanValidator.isValid("[E89370400440532013000")).isFalse(); // > 'Z'
		assertThat(IbanValidator.isValid("D@89370400440532013000")).isFalse();
		assertThat(IbanValidator.isValid("D[89370400440532013000")).isFalse(); // > 'Z'

		// Non-digit check digits (< '0' and > '9')
		assertThat(IbanValidator.isValid("DE/9370400440532013000")).isFalse(); // < '0'
		assertThat(IbanValidator.isValid("DE:9370400440532013000")).isFalse(); // > '9'
		assertThat(IbanValidator.isValid("DE8/370400440532013000")).isFalse(); // < '0'
		assertThat(IbanValidator.isValid("DE8:370400440532013000")).isFalse(); // > '9'
		assertThat(IbanValidator.isValid("DEAA370400440532013000")).isFalse();
		assertThat(IbanValidator.isValid("DE8A370400440532013000")).isFalse();
		assertThat(IbanValidator.isValid("DEA8370400440532013000")).isFalse();
	}

	@Test
	@DisplayName("returns false on invalid non-alphanumeric characters in payload body")
	void invalidCharacterInPayload() {
		// Character '?' in body
		assertThat(IbanValidator.isValid("DE8937040044?532013000")).isFalse();
		// Character '#' in country/check block moved to end
		assertThat(IbanValidator.isValid("DE893704004405320130#0")).isFalse();
		// Lowercase letter in body (c >= 'A' but c > 'Z')
		assertThat(IbanValidator.isValid("DE8937040044a532013000")).isFalse();
	}

	@Test
	@DisplayName("validates valid German and UK IBANs with spaces and hyphens")
	void validIbansWithFormatting() {
		assertThat(IbanValidator.isValid(VALID_IBAN_DE)).isTrue();
		assertThat(IbanValidator.isValid("DE89 3704 0044 0532 0130 00")).isTrue();
		assertThat(IbanValidator.isValid("DE89-3704-0044-0532-0130-00")).isTrue();

		assertThat(IbanValidator.isValid(VALID_IBAN_GB)).isTrue();
		assertThat(IbanValidator.isValid("GB29 NWBK 6016 1331 9268 19")).isTrue();
	}

	@Test
	@DisplayName("returns false when Mod-97-10 checksum fails")
	void invalidChecksum() {
		// DE89 modified to DE90
		assertThat(IbanValidator.isValid("DE90370400440532013000")).isFalse();
		// GB29 modified to GB30
		assertThat(IbanValidator.isValid("GB30NWBK60161331926819")).isFalse();
	}
}
