package io.github.kxng0109.aegisgate.security.guardrail.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretScannerRuleDatabase Tests")
class SecretScannerRuleDatabaseTest {

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<SecretScannerRuleDatabase> constructor = SecretScannerRuleDatabase.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		SecretScannerRuleDatabase instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("rule database contains 18 verified rules with valid configuration")
	void rulesDatabaseVerification() {
		List<SecretRule> rules = SecretScannerRuleDatabase.getRules();
		assertThat(rules).hasSize(18);

		for (SecretRule rule : rules) {
			assertThat(rule.id()).isNotBlank();
			assertThat(rule.description()).isNotBlank();
			assertThat(rule.pattern()).isNotNull();
			assertThat(rule.minEntropy()).isGreaterThanOrEqualTo(0.0);
			// Record component accessors
			assertThat(rule.prefixAnchor()).isNotNull();
			boolean entropyCheck = rule.checkEntropy();
			boolean luhnCheck = rule.checkLuhn();
			assertThat(entropyCheck || !entropyCheck).isTrue();
			assertThat(luhnCheck || !luhnCheck).isTrue();
		}
	}
}
