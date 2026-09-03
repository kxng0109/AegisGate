package io.github.kxng0109.aegisgate.security.compliance;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeoSovereigntyRouter Tests")
class GeoSovereigntyRouterTest {

	private final GeoSovereigntyRouter router = new GeoSovereigntyRouter();

	private ProviderConfig createConfig(String name, String uriStr) {
		return new ProviderConfig(
				name,
				ProviderType.OPENAI,
				URI.create(uriStr),
				new SensitiveString("secret"),
				Duration.ofSeconds(2),
				Duration.ofSeconds(10)
		);
	}

	@Test
	@DisplayName("explicit registration takes precedence over URI heuristics")
	void explicitRegistrationPrecedence() {
		router.registerJurisdiction("custom-provider", Jurisdiction.EU);
		ProviderConfig usConfig = createConfig("custom-provider", "https://api.openai.com/v1");

		Jurisdiction resolved = router.resolveJurisdiction("custom-provider", usConfig);
		assertThat(resolved).isEqualTo(Jurisdiction.EU);
		assertThat(router.resolveJurisdiction(null, usConfig)).isEqualTo(Jurisdiction.US);
		assertThat(router.resolveJurisdiction("custom-provider", null)).isEqualTo(Jurisdiction.EU);
		assertThat(router.resolveJurisdiction("unregistered", null)).isEqualTo(Jurisdiction.GLOBAL);

		// Nulls ignored safely
		router.registerJurisdiction(null, Jurisdiction.US);
		router.registerJurisdiction("provider", null);
	}

	@Test
	@DisplayName("heuristic analysis identifies EU, NG, UK, CH, and US from domain and naming patterns")
	void heuristicDomainAndNamingResolution() {
		// EU variations: .eu, -eu, _eu, eu-
		assertThat(router.resolveJurisdiction(
				"my-ai",
				createConfig("my-ai", "https://inference.company.eu")
		)).isEqualTo(Jurisdiction.EU);
		assertThat(router.resolveJurisdiction(
				"azure-eu",
				createConfig("azure-eu", "https://eastus.azure.com")
		)).isEqualTo(Jurisdiction.EU);
		assertThat(router.resolveJurisdiction(
				"azure_eu",
				createConfig("azure_eu", "https://eastus.azure.com")
		)).isEqualTo(Jurisdiction.EU);
		assertThat(router.resolveJurisdiction(
				"eu-central",
				createConfig("eu-central", "https://eastus.azure.com")
		)).isEqualTo(Jurisdiction.EU);

		// NG variations: .ng, -ng, _ng, ng-
		assertThat(router.resolveJurisdiction(
				"local-hub",
				createConfig("local-hub", "https://ai.fintech.ng")
		)).isEqualTo(Jurisdiction.NG);
		assertThat(router.resolveJurisdiction(
				"provider-ng",
				createConfig("provider-ng", "https://cloud.com")
		)).isEqualTo(Jurisdiction.NG);
		assertThat(router.resolveJurisdiction(
				"provider_ng",
				createConfig("provider_ng", "https://cloud.com")
		)).isEqualTo(Jurisdiction.NG);
		assertThat(router.resolveJurisdiction("ng-west", createConfig("ng-west", "https://cloud.com"))).isEqualTo(
				Jurisdiction.NG);

		// UK variations: .uk, -uk, _uk, uk-
		assertThat(router.resolveJurisdiction("cloud-uk", createConfig("cloud-uk", "https://ai.gov.uk"))).isEqualTo(
				Jurisdiction.UK);
		assertThat(router.resolveJurisdiction("ai-uk", createConfig("ai-uk", "https://cloud.com"))).isEqualTo(
				Jurisdiction.UK);
		assertThat(router.resolveJurisdiction("ai_uk", createConfig("ai_uk", "https://cloud.com"))).isEqualTo(
				Jurisdiction.UK);
		assertThat(router.resolveJurisdiction("uk-london", createConfig("uk-london", "https://cloud.com"))).isEqualTo(
				Jurisdiction.UK);

		// CH variations: .ch, -ch, _ch, ch-
		assertThat(router.resolveJurisdiction(
				"swiss-vault",
				createConfig("swiss-vault", "https://private.bank.ch")
		)).isEqualTo(Jurisdiction.CH);
		assertThat(router.resolveJurisdiction("ai-ch", createConfig("ai-ch", "https://cloud.com"))).isEqualTo(
				Jurisdiction.CH);
		assertThat(router.resolveJurisdiction("ai_ch", createConfig("ai_ch", "https://cloud.com"))).isEqualTo(
				Jurisdiction.CH);
		assertThat(router.resolveJurisdiction("ch-zurich", createConfig("ch-zurich", "https://cloud.com"))).isEqualTo(
				Jurisdiction.CH);

		// US variations: -us, _us, us-, openai.com, anthropic.com
		assertThat(router.resolveJurisdiction(
				"openai-us",
				createConfig("openai-us", "https://api.openai.com/v1")
		)).isEqualTo(Jurisdiction.US);
		assertThat(router.resolveJurisdiction(
				"claude",
				createConfig("claude", "https://api.anthropic.com/v1")
		)).isEqualTo(Jurisdiction.US);
		assertThat(router.resolveJurisdiction("ai_us", createConfig("ai_us", "https://cloud.com"))).isEqualTo(
				Jurisdiction.US);
		assertThat(router.resolveJurisdiction("us-east", createConfig("us-east", "https://cloud.com"))).isEqualTo(
				Jurisdiction.US);

		// Null host in URI and null name
		ProviderConfig nullHostConfig = createConfig("local", "/relative/path");
		assertThat(router.resolveJurisdiction("local", nullHostConfig)).isEqualTo(Jurisdiction.GLOBAL);
		ProviderConfig nullNameConfig = createConfig(null, "https://api.example.com");
		assertThat(router.resolveJurisdiction("unregistered", nullNameConfig)).isEqualTo(Jurisdiction.GLOBAL);

		// Fallback to GLOBAL
		ProviderConfig generic = createConfig("generic", "https://api.example.com");
		assertThat(router.resolveJurisdiction("generic", generic)).isEqualTo(Jurisdiction.GLOBAL);
		assertThat(router.resolveJurisdiction("generic", null)).isEqualTo(Jurisdiction.GLOBAL);
	}

	@Test
	@DisplayName("filterChain returns empty list on null or empty chain")
	void filterChainEmptyOrNull() {
		assertThat(router.filterChain(
				null,
				Map.of(),
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.EU,
				"gpt-4o"
		)).isEmpty();
		assertThat(router.filterChain(
				List.of(),
				Map.of(),
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.EU,
				"gpt-4o"
		)).isEmpty();
	}

	@Test
	@DisplayName("filterChain passes chain through untouched when policy or origin is null or GLOBAL")
	void filterChainBypass() {
		List<ProviderRef> chain = List.of(new ProviderRef("us-provider", null));
		assertThat(router.filterChain(chain, Map.of(), null, Jurisdiction.EU, "gpt-4o")).isSameAs(chain);
		assertThat(router.filterChain(
				chain,
				Map.of(),
				ResidencyPolicy.STRICT_SOVEREIGN,
				null,
				"gpt-4o"
		)).isSameAs(chain);
		assertThat(router.filterChain(
				chain,
				Map.of(),
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.GLOBAL,
				"gpt-4o"
		)).isSameAs(chain);
	}

	@Test
	@DisplayName("STRICT_SOVEREIGN allows matching origin or GLOBAL and fails closed with HTTP 503 exception when none remain")
	void strictSovereignPolicyFiltering() {
		ProviderConfig euConfig = createConfig("eu-1", "https://api.provider.eu");
		ProviderConfig usConfig = createConfig("us-1", "https://api.openai.com");
		Map<String, ProviderConfig> providers = Map.of("eu-1", euConfig, "us-1", usConfig);

		List<ProviderRef> chain = List.of(new ProviderRef("eu-1", null), new ProviderRef("us-1", null));

		// EU origin keeps only eu-1
		List<ProviderRef> filtered = router.filterChain(
				chain,
				providers,
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.EU,
				"gpt-4o"
		);
		assertThat(filtered).extracting(ProviderRef::providerName).containsExactly("eu-1");

		// NG origin has no matching provider -> throws DataResidencyBreachException
		assertThatThrownBy(() -> router.filterChain(
				chain,
				providers,
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.NG,
				"gpt-4o"
		))
				.isInstanceOf(DataResidencyBreachException.class)
				.hasMessageContaining("designated sovereign zone [NG]");
	}

	@Test
	@DisplayName("SOVEREIGN_CASCADE allows adequate jurisdictions and drops inadequate ones")
	void sovereignCascadePolicyFiltering() {
		ProviderConfig ukConfig = createConfig("uk-1", "https://api.cloud.uk");
		ProviderConfig usConfig = createConfig("us-1", "https://api.openai.com");
		Map<String, ProviderConfig> providers = Map.of("uk-1", ukConfig, "us-1", usConfig);

		List<ProviderRef> chain = List.of(new ProviderRef("uk-1", null), new ProviderRef("us-1", null));

		// EU origin allows UK (UK is adequate for EU), excludes US
		List<ProviderRef> filtered = router.filterChain(
				chain,
				providers,
				ResidencyPolicy.SOVEREIGN_CASCADE,
				Jurisdiction.EU,
				"gpt-4o"
		);
		assertThat(filtered).extracting(ProviderRef::providerName).containsExactly("uk-1");

		// Cascade fails closed when no adequate provider remains
		assertThatThrownBy(() -> router.filterChain(
				List.of(new ProviderRef("us-1", null)),
				providers,
				ResidencyPolicy.SOVEREIGN_CASCADE,
				Jurisdiction.EU,
				"gpt-4o"
		))
				.isInstanceOf(DataResidencyBreachException.class)
				.hasMessageContaining("designated sovereign zone [EU]");

		// STRICT_SOVEREIGN keeps Jurisdiction.GLOBAL providers (line 112)
		ProviderConfig globalConfig = createConfig("global-1", "https://api.example.com");
		Map<String, ProviderConfig> withGlobal = Map.of("global-1", globalConfig);
		List<ProviderRef> keptGlobal = router.filterChain(
				List.of(new ProviderRef("global-1", null)),
				withGlobal,
				ResidencyPolicy.STRICT_SOVEREIGN,
				Jurisdiction.EU,
				"gpt-4o"
		);
		assertThat(keptGlobal).hasSize(1);
	}

	@Test
	@DisplayName("PERMISSIVE_FAILOVER_WITH_AUDIT allows all candidate providers without throwing exception")
	void permissiveFailoverAllowsAll() {
		ProviderConfig usConfig = createConfig("us-1", "https://api.openai.com");
		Map<String, ProviderConfig> providers = Map.of("us-1", usConfig);

		List<ProviderRef> chain = List.of(new ProviderRef("us-1", null));
		List<ProviderRef> filtered = router.filterChain(
				chain,
				providers,
				ResidencyPolicy.PERMISSIVE_FAILOVER_WITH_AUDIT,
				Jurisdiction.EU,
				"gpt-4o"
		);
		assertThat(filtered).hasSize(1);

		// Permissive retains candidate provider even when unregistered in providers map
		List<ProviderRef> unregFiltered = router.filterChain(
				List.of(new ProviderRef("unknown", null)),
				Map.of(),
				ResidencyPolicy.PERMISSIVE_FAILOVER_WITH_AUDIT,
				Jurisdiction.EU,
				"gpt-4o"
		);
		assertThat(unregFiltered).hasSize(1);
	}
}
