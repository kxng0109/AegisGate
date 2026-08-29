package io.github.kxng0109.aegisgate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveStringTest {

	@Test
	@DisplayName("toString masks the wrapped value")
	void toStringMasksTheWrappedValue() {
		SensitiveString secret = new SensitiveString("sk-real-secret-key");

		assertEquals("****", secret.toString());
		assertFalse(secret.toString().contains("sk-real-secret-key"));
	}

	@Test
	@DisplayName("string concatenation cannot leak the value")
	void stringConcatenationCannotLeakTheValue() {
		SensitiveString secret = new SensitiveString("sk-real-secret-key");

		String logged = "apiKey=" + secret;
		assertFalse(logged.contains("sk-real-secret-key"));
		assertTrue(logged.endsWith("apiKey=****"));
	}

	@Test
	@DisplayName("record contract: accessors, equality, and hash code expose or compare the raw value deliberately")
	void recordContractHolds() {
		SensitiveString secret = new SensitiveString("sk-a");

		assertEquals("sk-a", secret.value());
		assertEquals(new SensitiveString("sk-a"), secret);
		assertEquals(secret.hashCode(), new SensitiveString("sk-a").hashCode());
		assertNotEquals(new SensitiveString("sk-b"), secret);

		assertAll(
				() -> assertEquals("sk-a", secret.value()),
				() -> assertNotEquals(new SensitiveString("sk-b"), secret)
		);
	}
}
