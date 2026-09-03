package io.github.kxng0109.aegisgate.security.guardrail.common;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShannonEntropyCalculator Tests")
class ShannonEntropyCalculatorTest {

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<ShannonEntropyCalculator> constructor = ShannonEntropyCalculator.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		ShannonEntropyCalculator instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("calculate(byte[]) returns 0.0 on invalid boundary conditions")
	void calculateBytesBoundaryChecks() {
		byte[] nullBytes = null;
		assertThat(ShannonEntropyCalculator.calculate(nullBytes, 0, 10)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate(new byte[10], 0, 0)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate(new byte[10], 0, 1)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate(new byte[10], -1, 5)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate(new byte[10], 5, 6)).isEqualTo(0.0);
	}

	@Test
	@DisplayName("calculate(CharSequence) returns 0.0 on invalid boundary conditions")
	void calculateCharSequenceBoundaryChecks() {
		CharSequence nullSeq = null;
		assertThat(ShannonEntropyCalculator.calculate(nullSeq)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate("")).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate("a")).isEqualTo(0.0);
	}

	@Test
	@DisplayName("uniform repetition yields 0.0 entropy")
	void uniformRepetitionZeroEntropy() {
		byte[] allA = "AAAAAAAAAAAAAAAA".getBytes(StandardCharsets.UTF_8);
		assertThat(ShannonEntropyCalculator.calculate(allA, 0, allA.length)).isEqualTo(0.0);
		assertThat(ShannonEntropyCalculator.calculate("AAAAAAAAAAAAAAAA")).isEqualTo(0.0);
	}

	@Test
	@DisplayName("two equiprobable symbols yield 1.0 bit entropy")
	void twoEquiprobableSymbolsOneBitEntropy() {
		byte[] binary = "01010101".getBytes(StandardCharsets.UTF_8);
		double entropyBytes = ShannonEntropyCalculator.calculate(binary, 0, binary.length);
		double entropyChars = ShannonEntropyCalculator.calculate("01010101");

		assertThat(entropyBytes).isCloseTo(1.0, Offset.offset(0.001));
		assertThat(entropyChars).isCloseTo(1.0, Offset.offset(0.001));
	}

	@Test
	@DisplayName("high-entropy cryptographic token yields high entropy (>= 4.2)")
	void highEntropyToken() {
		String secret = "sk-proj-aB9zY1kL0pQ8wE2rT5yU7iO4aS6dF8gH1jK3lZ5xX7cV9bN0mQ2wE4rT6yU8iO0";
		double entropy = ShannonEntropyCalculator.calculate(secret);
		assertThat(entropy).isGreaterThan(4.2);
	}

	@Test
	@DisplayName("byte buffer exceeding 256 bytes with symbol count > 256 covers else branch")
	void bufferExceeding256Bytes() {
		byte[] largeBuffer = new byte[300];
		Arrays.fill(largeBuffer, (byte) 'Z');
		double entropy = ShannonEntropyCalculator.calculate(largeBuffer, 0, largeBuffer.length);
		assertThat(entropy).isEqualTo(0.0);

		// With two symbols where one exceeds 256
		Arrays.fill(largeBuffer, 0, 270, (byte) 'A');
		Arrays.fill(largeBuffer, 270, 300, (byte) 'B');
		double entropyMixed = ShannonEntropyCalculator.calculate(largeBuffer, 0, largeBuffer.length);
		assertThat(entropyMixed).isGreaterThan(0.0);
	}

	@Test
	@DisplayName("CharSequence exceeding 256 chars with symbol count > 256 covers else branch")
	void charSequenceExceeding256Chars() {
		String largeStr = "A".repeat(300);
		double entropy = ShannonEntropyCalculator.calculate(largeStr);
		assertThat(entropy).isEqualTo(0.0);

		String largeMixed = "A".repeat(270) + "B".repeat(30);
		double entropyMixed = ShannonEntropyCalculator.calculate(largeMixed);
		assertThat(entropyMixed).isGreaterThan(0.0);
	}
}
