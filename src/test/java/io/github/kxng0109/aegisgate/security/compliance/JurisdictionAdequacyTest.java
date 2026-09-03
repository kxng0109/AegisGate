package io.github.kxng0109.aegisgate.security.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Jurisdiction & ResidencyPolicy Tests")
class JurisdictionAdequacyTest {

	@Test
	@DisplayName("ResidencyPolicy enum values and valueOf coverage")
	void residencyPolicyEnum() {
		assertThat(ResidencyPolicy.valueOf("STRICT_SOVEREIGN")).isEqualTo(ResidencyPolicy.STRICT_SOVEREIGN);
		assertThat(ResidencyPolicy.valueOf("SOVEREIGN_CASCADE")).isEqualTo(ResidencyPolicy.SOVEREIGN_CASCADE);
		assertThat(ResidencyPolicy.valueOf("PERMISSIVE_FAILOVER_WITH_AUDIT")).isEqualTo(ResidencyPolicy.PERMISSIVE_FAILOVER_WITH_AUDIT);
		assertThat(ResidencyPolicy.values()).hasSize(3);
	}

	@Test
	@DisplayName("Jurisdiction enum values and valueOf coverage")
	void jurisdictionEnumValues() {
		for (Jurisdiction j : Jurisdiction.values()) {
			assertThat(Jurisdiction.valueOf(j.name())).isEqualTo(j);
		}
	}

	@Test
	@DisplayName("DataResidencyBreachException stores jurisdiction and model and formats message")
	void dataResidencyBreachException() {
		DataResidencyBreachException ex = new DataResidencyBreachException(Jurisdiction.EU, "gpt-4o");
		assertThat(ex.getJurisdiction()).isEqualTo(Jurisdiction.EU);
		assertThat(ex.getModel()).isEqualTo("gpt-4o");
		assertThat(ex.getMessage()).contains("sovereign zone [EU]").contains("model [gpt-4o]");
	}

	@Test
	@DisplayName("isAdequate returns true when origin, target, or either is null or GLOBAL")
	void nullOrGlobalAdequacy() {
		assertThat(Jurisdiction.isAdequate(null, Jurisdiction.EU)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, null)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.GLOBAL, Jurisdiction.EU)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.GLOBAL)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.GLOBAL, Jurisdiction.GLOBAL)).isTrue();
	}

	@Test
	@DisplayName("isAdequate returns true on identity match (origin == target)")
	void identityAdequacy() {
		for (Jurisdiction j : Jurisdiction.values()) {
			assertThat(Jurisdiction.isAdequate(j, j)).isTrue();
		}
	}

	@Test
	@DisplayName("EU adequacy allows EU, CH, UK, CA but denies US and NG")
	void euAdequacyRules() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.CH)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.UK)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.CA)).isTrue();

		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.US)).isFalse();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.EU, Jurisdiction.NG)).isFalse();
	}

	@Test
	@DisplayName("NG adequacy allows NG, EU, UK, ZA, GH under NDPA 2023 but denies US and CH")
	void ngAdequacyRules() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.EU)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.UK)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.ZA)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.GH)).isTrue();

		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.US)).isFalse();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.NG, Jurisdiction.CH)).isFalse();
	}

	@Test
	@DisplayName("UK adequacy allows UK, EU, CH but denies US, NG, and CA")
	void ukAdequacyRules() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.UK, Jurisdiction.EU)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.UK, Jurisdiction.CH)).isTrue();

		assertThat(Jurisdiction.isAdequate(Jurisdiction.UK, Jurisdiction.US)).isFalse();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.UK, Jurisdiction.NG)).isFalse();
	}

	@Test
	@DisplayName("US adequacy permits only US")
	void usAdequacyRules() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.US, Jurisdiction.US)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.US, Jurisdiction.EU)).isFalse();
	}

	@Test
	@DisplayName("CH adequacy allows CH and EU but denies US and UK")
	void chAdequacyRules() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.CH, Jurisdiction.EU)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.CH, Jurisdiction.US)).isFalse();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.CH, Jurisdiction.UK)).isFalse();
	}

	@Test
	@DisplayName("default jurisdiction switch branch (e.g. CA, ZA, GH) uses strict identity")
	void defaultJurisdictionsStrictIdentity() {
		assertThat(Jurisdiction.isAdequate(Jurisdiction.CA, Jurisdiction.CA)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.CA, Jurisdiction.EU)).isFalse();

		assertThat(Jurisdiction.isAdequate(Jurisdiction.ZA, Jurisdiction.ZA)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.ZA, Jurisdiction.NG)).isFalse();

		assertThat(Jurisdiction.isAdequate(Jurisdiction.GH, Jurisdiction.GH)).isTrue();
		assertThat(Jurisdiction.isAdequate(Jurisdiction.GH, Jurisdiction.EU)).isFalse();
	}
}
