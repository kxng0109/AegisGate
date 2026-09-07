package io.github.kxng0109.aegisgate.cache.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheGuardrails slot-aligned contradiction adversarial matrix")
class CacheGuardrailsAdversarialTest {

	private final CacheGuardrails guardrails = new CacheGuardrails();

	@Test
	@DisplayName("paraphrase pass: same entities under different wording share no slot")
	void paraphrasePass() {
		assertThat(guardrails.checkEntityMatch(
				"Who is the CEO of Apple?", "Tell me the Apple CEO")).isTrue();
	}

	@ParameterizedTest(name = "reject: {0} || {1}")
	@CsvSource({
			"'CEO of Apple', 'CEO of Microsoft'",
			"'Deploy Docker on AWS', 'Deploy Docker on Azure'",
			"'Calculate 42*2', 'Calculate 100*2'"
	})
	@DisplayName("slot-swap reject: shared slot with disjoint values")
	void slotSwapReject(String incoming, String cached) {
		assertThat(guardrails.checkEntityMatch(incoming, cached)).isFalse();
		assertThat(guardrails.validateSemanticMatch(incoming, cached, false, true)).isFalse();
		assertThat(guardrails.validateSemanticMatch(incoming, cached, false, false)).isTrue();
	}

	@Test
	@DisplayName("article-trap: articles are skipped, slot falls to nearest content word")
	void articleTrap() {
		assertThat(guardrails.checkEntityMatch(
				"Who is the CEO?", "Tell me about the Apple?")).isTrue();
		assertThat(guardrails.checkEntityMatch("the CEO", "the Apple")).isFalse();
	}

	@ParameterizedTest(name = "asymmetric reject: {0} || {1}")
	@CsvSource({
			"'Here is number 42', 'No numbers here'",
			"'No numbers here', 'Here is number 42'",
			"'Mentions France and Paris', 'all lowercase words'",
			"'all lowercase words', 'Mentions France and Paris'"
	})
	@DisplayName("asymmetric reject: one-sided numbers or entities")
	void asymmetricReject(String incoming, String cached) {
		assertThat(guardrails.checkEntityMatch(incoming, cached)).isFalse();
	}

	@Test
	@DisplayName("SECURITY-LIMITATION PIN: lowercase evasion bypasses capitalized-entity regex")
	void lowercaseEvasionPinned() {
		assertThat(guardrails.checkEntityMatch(
				"deploy docker on aws", "deploy docker on azure")).isTrue();
		assertThat(guardrails.checkEntityMatch(
				"pay forty two dollars", "pay one hundred dollars")).isTrue();
		assertThat(guardrails.checkEntityMatch(
				"Deploy Docker on AWS", "Deploy Docker on Azure")).isFalse();
	}

	@ParameterizedTest(name = "numbers: {0} || {1} -> {2}")
	@CsvSource({
			"'Pay 3.14 dollars', 'Pay 3.14 dollars', 'true'",
			"'Pay 3.14 dollars', 'Pay 3.15 dollars', 'false'",
			"'Pay 007 dollars', 'Pay 007 dollars', 'true'",
			"'Pay 007 dollars', 'Pay 7 dollars', 'false'",
			"'Pay 42 dollars', 'Pay 42.0 dollars', 'false'",
			"'Count 007 and 42', 'Count 007 and 42', 'true'"
	})
	@DisplayName("number-format cases: decimals atomic, leading zeros significant")
	void numberFormats(String incoming, String cached, boolean expected) {
		assertThat(guardrails.checkEntityMatch(incoming, cached)).isEqualTo(expected);
	}

	@Test
	@DisplayName("no shared slot passes for compatible prompts with disjoint contexts")
	void noSharedSlotPass() {
		assertThat(guardrails.checkEntityMatch(
				"Explain photosynthesis in Paris", "Tell me about Docker")).isTrue();
	}
}
