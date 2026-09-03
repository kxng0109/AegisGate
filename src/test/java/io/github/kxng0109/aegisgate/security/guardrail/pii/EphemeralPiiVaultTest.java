package io.github.kxng0109.aegisgate.security.guardrail.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EphemeralPiiVault Tests")
class EphemeralPiiVaultTest {

	@Test
	@DisplayName("stores, encrypts, and successfully resolves plaintext PII")
	void storeAndResolve() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			assertThat(vault.isEmpty()).isTrue();

			vault.store("<PERSON_1>", "Alice Smith");
			vault.store("<EMAIL_1>", "alice@example.com");

			assertThat(vault.isEmpty()).isFalse();
			assertThat(vault.resolve("<PERSON_1>")).isEqualTo("Alice Smith");
			assertThat(vault.resolve("<EMAIL_1>")).isEqualTo("alice@example.com");
			assertThat(vault.resolve("<NON_EXISTENT>")).isNull();

			assertThat(vault.getExistingSurrogate("Alice Smith")).isEqualTo("<PERSON_1>");
			assertThat(vault.getExistingSurrogate("Unknown")).isNull();
			assertThat(vault.getSurrogates()).containsExactlyInAnyOrder("<PERSON_1>", "<EMAIL_1>");
		}
	}

	@Test
	@DisplayName("store ignores null surrogate or null plaintext")
	void storeIgnoresNulls() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store(null, "Alice");
			vault.store("<PERSON_1>", null);
			assertThat(vault.isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("close is idempotent and zero-wipes memory")
	void closeWipesMemoryAndIsIdempotent() {
		EphemeralPiiVault vault = new EphemeralPiiVault();
		vault.store("<PERSON_1>", "Sensitive Secret");
		assertThat(vault.isEmpty()).isFalse();

		vault.close();
		// Idempotent second close
		vault.close();

		assertThat(vault.isEmpty()).isTrue();

		// Operations after close throw IllegalStateException
		assertThatThrownBy(() -> vault.store("<PERSON_2>", "Bob"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Vault is closed");

		assertThatThrownBy(() -> vault.resolve("<PERSON_1>"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Vault is closed");
	}
}
