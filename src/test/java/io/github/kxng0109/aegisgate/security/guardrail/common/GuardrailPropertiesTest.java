package io.github.kxng0109.aegisgate.security.guardrail.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuardrailProperties & GuardrailMode Tests")
class GuardrailPropertiesTest {

	@Test
	@DisplayName("GuardrailMode enum values and valueOf")
	void guardrailModeEnum() {
		assertThat(GuardrailMode.valueOf("ENFORCE")).isEqualTo(GuardrailMode.ENFORCE);
		assertThat(GuardrailMode.valueOf("AUDIT_ONLY")).isEqualTo(GuardrailMode.AUDIT_ONLY);
		assertThat(GuardrailMode.values()).containsExactly(GuardrailMode.ENFORCE, GuardrailMode.AUDIT_ONLY);
	}

	@Test
	@DisplayName("default properties values are secure and enabled")
	void defaultProperties() {
		GuardrailProperties props = new GuardrailProperties();
		assertThat(props.getMode()).isEqualTo(GuardrailMode.ENFORCE);
		assertThat(props.isSecretScanningEnabled()).isTrue();
		assertThat(props.isPiiAnonymizationEnabled()).isTrue();
		assertThat(props.isPromptInjectionDefenseEnabled()).isTrue();
		assertThat(props.isStreamingValidationEnabled()).isTrue();
		assertThat(props.getKillSwitchMode()).isEqualTo("TERMINATE_WITH_ERROR");
		assertThat(props.isSystemPromptExfiltrationDefenseEnabled()).isTrue();
		assertThat(props.isDataResidencyEnabled()).isTrue();
		assertThat(props.getDefaultResidencyPolicy()).isEqualTo("STRICT_SOVEREIGN");
	}

	@Test
	@DisplayName("setters and getters work and nulls fallback to defaults")
	void mutatorsAndNullFallbacks() {
		GuardrailProperties props = new GuardrailProperties();

		props.setMode(GuardrailMode.AUDIT_ONLY);
		assertThat(props.getMode()).isEqualTo(GuardrailMode.AUDIT_ONLY);
		props.setMode(null);
		assertThat(props.getMode()).isEqualTo(GuardrailMode.ENFORCE);

		props.setSecretScanningEnabled(false);
		assertThat(props.isSecretScanningEnabled()).isFalse();

		props.setPiiAnonymizationEnabled(false);
		assertThat(props.isPiiAnonymizationEnabled()).isFalse();

		props.setPromptInjectionDefenseEnabled(false);
		assertThat(props.isPromptInjectionDefenseEnabled()).isFalse();

		props.setStreamingValidationEnabled(false);
		assertThat(props.isStreamingValidationEnabled()).isFalse();

		props.setKillSwitchMode("GRACEFUL_FILTER_TERMINATION");
		assertThat(props.getKillSwitchMode()).isEqualTo("GRACEFUL_FILTER_TERMINATION");
		props.setKillSwitchMode(null);
		assertThat(props.getKillSwitchMode()).isEqualTo("TERMINATE_WITH_ERROR");

		props.setSystemPromptExfiltrationDefenseEnabled(false);
		assertThat(props.isSystemPromptExfiltrationDefenseEnabled()).isFalse();

		props.setDataResidencyEnabled(false);
		assertThat(props.isDataResidencyEnabled()).isFalse();

		props.setDefaultResidencyPolicy("SOVEREIGN_CASCADE");
		assertThat(props.getDefaultResidencyPolicy()).isEqualTo("SOVEREIGN_CASCADE");
		props.setDefaultResidencyPolicy(null);
		assertThat(props.getDefaultResidencyPolicy()).isEqualTo("STRICT_SOVEREIGN");
	}
}
