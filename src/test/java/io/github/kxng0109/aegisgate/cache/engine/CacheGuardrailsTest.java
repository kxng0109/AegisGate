package io.github.kxng0109.aegisgate.cache.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheGuardrails")
class CacheGuardrailsTest {

	private final CacheGuardrails guardrails = new CacheGuardrails();

	@Test
	@DisplayName("checkPolarityMatch correctly detects matching and conflicting polarity keywords")
	void polarityMatchTests() {
		// Matching intent
		assertThat(guardrails.checkPolarityMatch("How to enable 2FA", "Please enable two factor auth")).isTrue();
		assertThat(guardrails.checkPolarityMatch("How to reset my password", "I forgot my password")).isTrue();

		// Opposing polarity pairs
		assertThat(guardrails.checkPolarityMatch("How to enable 2FA", "How to disable 2FA")).isFalse();
		assertThat(guardrails.checkPolarityMatch("disable 2FA", "enable 2FA")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Turn on dark mode", "Turn off dark mode")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Start the container", "Stop the container")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Create an account", "Delete an account")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Add item to cart", "Remove item from cart")).isFalse();

		// General negation mismatch
		assertThat(guardrails.checkPolarityMatch("Is Python compiled?", "Is Python not compiled?")).isFalse();
		assertThat(guardrails.checkPolarityMatch("I want milk", "I want coffee without milk")).isFalse();
	}

	@Test
	@DisplayName("checkEntityMatch correctly detects matching and conflicting entities and numbers")
	void entityMatchTests() {
		// Same entities
		assertThat(guardrails.checkEntityMatch("Who is the CEO of Apple?", "Tell me the Apple CEO")).isTrue();

		// Different named entities (Proper Nouns)
		assertThat(guardrails.checkEntityMatch("Who is the CEO of Apple?", "Who is the CEO of Microsoft?")).isFalse();
		assertThat(guardrails.checkEntityMatch("Deploy Docker on AWS", "Deploy Docker on Azure")).isFalse();

		// Same numbers
		assertThat(guardrails.checkEntityMatch("Calculate 42 * 2", "What is 42 times 2?")).isTrue();

		// Different numbers
		assertThat(guardrails.checkEntityMatch("Calculate 42 * 2", "Calculate 100 * 2")).isFalse();

		// Asymmetric empty entity and number sets
		assertThat(guardrails.checkEntityMatch("No numbers here", "Here is number 42")).isFalse();
		assertThat(guardrails.checkEntityMatch("Here is number 42", "No numbers here")).isFalse();
		assertThat(guardrails.checkEntityMatch("all lowercase words", "Mentions France and Paris")).isFalse();
		assertThat(guardrails.checkEntityMatch("Mentions France and Paris", "all lowercase words")).isFalse();
	}

	@Test
	@DisplayName("validateSemanticMatch respects configuration toggles")
	void validateSemanticMatchToggles() {
		// Conflicting polarity: fails when polarity guard enabled
		assertThat(guardrails.validateSemanticMatch("enable 2FA", "disable 2FA", true, true)).isFalse();
		// Passes if polarity guard is disabled
		assertThat(guardrails.validateSemanticMatch("enable 2FA", "disable 2FA", false, false)).isTrue();

		// Conflicting entity: fails when entity guard enabled
		assertThat(guardrails.validateSemanticMatch("CEO of Apple", "CEO of Microsoft", true, true)).isFalse();
		// Passes if entity guard is disabled
		assertThat(guardrails.validateSemanticMatch("CEO of Apple", "CEO of Microsoft", false, false)).isTrue();
	}
}
