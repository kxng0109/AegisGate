package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SseLineGuardProperties}.
 */
@DisplayName("SseLineGuardProperties")
@SuppressWarnings("DataFlowIssue")
class SseLineGuardPropertiesTest {

	@Test
	@DisplayName("DEFAULTS uses the spec-recommended values")
	void defaultsUsesSpecValues() {
		SseLineGuardProperties defaults = SseLineGuardProperties.DEFAULTS;
		assertThat(defaults.enabled()).isTrue();
		assertThat(defaults.globalDefaultBytes()).isEqualTo(16384);
		assertThat(defaults.safetyMarginPercent()).isEqualTo(10);
		assertThat(defaults.action()).isEqualTo(SseLineGuard.Action.REJECT_LINE_AND_CLOSE);
		assertThat(defaults.perProvider()).isEmpty();
		assertThat(defaults.writeTimeout()).isEqualTo(Duration.ofSeconds(30));
		assertThat(defaults.writeTimeoutCheckInterval()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("globalDefaultBytes must be positive")
	void globalDefaultBytesMustBePositive() {
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 0, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(5)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("globalDefaultBytes");
	}

	@Test
	@DisplayName("safetyMarginPercent must be in 0-100")
	void safetyMarginPercentMustBeInRange() {
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 1024, 101, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(5)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("safetyMarginPercent");
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 1024, -1, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(5)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("safetyMarginPercent");
	}

	@Test
	@DisplayName("writeTimeout must not be null")
	void writeTimeoutMustNotBeNull() {
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 1024, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(), null, Duration.ofSeconds(5)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("writeTimeout");
	}

	@Test
	@DisplayName("writeTimeoutCheckInterval must not be null")
	void writeTimeoutCheckIntervalMustNotBeNull() {
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 1024, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(), Duration.ofSeconds(30), null
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("writeTimeoutCheckInterval");
	}

	@Test
	@DisplayName("perProvider must not be null")
	void perProviderMustNotBeNull() {
		assertThatThrownBy(() -> new SseLineGuardProperties(
				true, 1024, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				null, Duration.ofSeconds(30), Duration.ofSeconds(5)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("perProvider");
	}

	@Test
	@DisplayName("ProviderConfig validates positive values")
	void providerConfigValidates() {
		assertThatThrownBy(() -> new SseLineGuard.ProviderConfig(0, 1000, 1000))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SseLineGuard.ProviderConfig(1000, 0, 1000))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SseLineGuard.ProviderConfig(1000, 1000, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("ConfigSnapshot stores its fields")
	void configSnapshotStoresFields() {
		Map<SseLineGuard.ProviderType, SseLineGuard.ProviderConfig> perProvider = new HashMap<>();
		perProvider.put(SseLineGuard.ProviderType.OPENAI, new SseLineGuard.ProviderConfig(16384, 1000, 1_048_576));
		SseLineGuard.ConfigSnapshot snapshot = new SseLineGuard.ConfigSnapshot(
				16384, perProvider, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE);
		assertThat(snapshot.globalDefaultBytes()).isEqualTo(16384);
		assertThat(snapshot.perProvider()).containsKey(SseLineGuard.ProviderType.OPENAI);
		assertThat(snapshot.safetyMarginPercent()).isEqualTo(10);
		assertThat(snapshot.action()).isEqualTo(SseLineGuard.Action.REJECT_LINE_AND_CLOSE);
	}

	@Test
	@DisplayName("ProviderType.from maps contracts ProviderType to guard ProviderType")
	void providerTypeMapping() {
		assertThat(SseLineGuard.ProviderType.from(ProviderType.OPENAI))
				.isEqualTo(SseLineGuard.ProviderType.OPENAI);
		assertThat(SseLineGuard.ProviderType.from(ProviderType.ANTHROPIC))
				.isEqualTo(SseLineGuard.ProviderType.ANTHROPIC);
		assertThat(SseLineGuard.ProviderType.from(ProviderType.OLLAMA))
				.isEqualTo(SseLineGuard.ProviderType.OLLAMA);
		assertThat(SseLineGuard.ProviderType.from(null))
				.isEqualTo(SseLineGuard.ProviderType.UNKNOWN);
	}
}