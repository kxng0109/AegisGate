package io.github.kxng0109.aegisgate.security.guardrail.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LuhnValidator Tests")
class LuhnValidatorTest {

	// Valid 16-digit PAN (sum = 50, 50 % 10 == 0)
	private static final String VALID_PAN_16 = "4532015112830366";
	private static final String INVALID_PAN_16 = "4532015112830367";

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<LuhnValidator> constructor = LuhnValidator.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		LuhnValidator instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("isValid(CharSequence) boundary and null checks")
	void charSequenceBoundaryChecks() {
		assertThat(LuhnValidator.isValid((CharSequence) null)).isFalse();
		assertThat(LuhnValidator.isValid("")).isFalse();
		assertThat(LuhnValidator.isValid("12345678901")).isFalse(); // 11 digits
		assertThat(LuhnValidator.isValid("4532-0151-128")).isFalse(); // total < 12 digits
	}

	@Test
	@DisplayName("isValid(CharSequence) detects non-digit characters")
	void charSequenceNonDigits() {
		assertThat(LuhnValidator.isValid("453201511283036a")).isFalse();
		assertThat(LuhnValidator.isValid("453201511283036#")).isFalse();
	}

	@Test
	@DisplayName("isValid(CharSequence) returns true on valid 16-digit PAN with spaces and hyphens")
	void charSequenceValidPan() {
		assertThat(LuhnValidator.isValid(VALID_PAN_16)).isTrue();
		assertThat(LuhnValidator.isValid("4532 0151 1283 0366")).isTrue();
		assertThat(LuhnValidator.isValid("4532-0151-1283-0366")).isTrue();
	}

	@Test
	@DisplayName("isValid(CharSequence) returns false on invalid checksum")
	void charSequenceInvalidChecksum() {
		assertThat(LuhnValidator.isValid(INVALID_PAN_16)).isFalse();
	}

	@Test
	@DisplayName("isValid(byte[]) boundary checks")
	void byteArrayBoundaryChecks() {
		byte[] validBytes = VALID_PAN_16.getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid((byte[]) null, 0, 16)).isFalse();
		assertThat(LuhnValidator.isValid(validBytes, 0, 11)).isFalse();
		assertThat(LuhnValidator.isValid(validBytes, -1, 16)).isFalse();
		assertThat(LuhnValidator.isValid(validBytes, 5, 12)).isFalse(); // overflow
	}

	@Test
	@DisplayName("isValid(byte[]) detects non-digit bytes")
	void byteArrayNonDigits() {
		byte[] nonDigits = "453201511283036X".getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid(nonDigits, 0, nonDigits.length)).isFalse();
	}

	@Test
	@DisplayName("isValid(byte[]) validates valid PAN and skips hyphens and spaces")
	void byteArrayValid() {
		byte[] formatted = "4532-0151-1283-0366".getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid(formatted, 0, formatted.length)).isTrue();

		byte[] spaced = "4532 0151 1283 0366".getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid(spaced, 0, spaced.length)).isTrue();
	}

	@Test
	@DisplayName("isValid(byte[]) returns false on invalid checksum or insufficient digits with delimiters")
	void byteArrayInvalidChecksum() {
		byte[] invalid = INVALID_PAN_16.getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid(invalid, 0, invalid.length)).isFalse();

		byte[] fewDigits = "1234 - 5678 - 90".getBytes(StandardCharsets.UTF_8);
		assertThat(LuhnValidator.isValid(fewDigits, 0, fewDigits.length)).isFalse();
	}
}
