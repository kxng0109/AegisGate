package io.github.kxng0109.aegisgate.security.guardrail.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiAnonymizer & PiiType Tests")
class PiiAnonymizerTest {

	private final PiiScanner scanner = new PiiScanner();
	private final PiiAnonymizer anonymizer = new PiiAnonymizer(scanner);

	@Test
	@DisplayName("PiiType enum surrogate prefixes coverage")
	void piiTypePrefixes() {
		for (PiiType type : PiiType.values()) {
			assertThat(type.getSurrogatePrefix()).isNotBlank().startsWith("<").endsWith("_");
		}
	}

	@Test
	@DisplayName("returns original text on null, blank, or null vault")
	void boundaryChecks() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			assertThat(anonymizer.anonymize(null, vault)).isNull();
			assertThat(anonymizer.anonymize("", vault)).isEqualTo("");
			assertThat(anonymizer.anonymize("   ", vault)).isEqualTo("   ");
			assertThat(anonymizer.anonymize("Hello world", null)).isEqualTo("Hello world");
		}
	}

	@Test
	@DisplayName("returns clean text unchanged when no PII is detected")
	void cleanTextUnchanged() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			String clean = "Explain how virtual threads differ from operating system carrier threads.";
			String result = anonymizer.anonymize(clean, vault);
			assertThat(result).isEqualTo(clean);
			assertThat(vault.isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("replaces single and multiple PII entities with surrogate tokens")
	void anonymizesMultipleEntities() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			String prompt = "Contact Dr. John Doe at john.doe@example.com or reach Dr. Jane Roe at jane.roe@example.com.";
			String anonymized = anonymizer.anonymize(prompt, vault);

			assertThat(anonymized).contains("<PERSON_1>").contains("<EMAIL_1>");
			assertThat(anonymized).contains("<PERSON_2>").contains("<EMAIL_2>");
			assertThat(anonymized).doesNotContain("John Doe");
			assertThat(anonymized).doesNotContain("john.doe@example.com");

			// Verify reversibility from vault
			assertThat(vault.resolve("<PERSON_1>")).isIn("John Doe", "Jane Roe");
			assertThat(vault.resolve("<EMAIL_1>")).isIn("john.doe@example.com", "jane.roe@example.com");
		}
	}

	@Test
	@DisplayName("reuses existing surrogate token when the exact same entity appears multiple times")
	void reusesSurrogateForDuplicateEntity() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			String prompt = "Email contact: alice@corp.com. Please confirm with alice@corp.com again.";
			String anonymized = anonymizer.anonymize(prompt, vault);

			assertThat(anonymized).doesNotContain("alice@corp.com");
			assertThat(anonymized).contains("<EMAIL_1>");
			assertThat(anonymized).doesNotContain("<EMAIL_2>");

			assertThat(vault.resolve("<EMAIL_1>")).isEqualTo("alice@corp.com");
		}
	}
}
