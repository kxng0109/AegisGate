package io.github.kxng0109.aegisgate.security.compliance;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geo-sovereignty and data residency routing filter.
 *
 * <p>Enforces national and international data boundary regulations (such as GDPR Art. 44-49,
 * HIPAA Domestic Boundary, and NDPA 2023 Sec. 41-43) by validating upstream provider jurisdictions before invoking
 * circuit breakers or initiating network requests.</p>
 */
@Component
public class GeoSovereigntyRouter {

	private final Map<String, Jurisdiction> providerJurisdictions = new ConcurrentHashMap<>();

	public GeoSovereigntyRouter() {
	}

	/**
	 * Explicitly registers a provider's physical regulatory jurisdiction.
	 */
	public void registerJurisdiction(String providerName, Jurisdiction jurisdiction) {
		if (providerName != null && jurisdiction != null) {
			providerJurisdictions.put(providerName.toLowerCase(Locale.ROOT), jurisdiction);
		}
	}

	/**
	 * Resolves the jurisdiction of a provider from registry or URI heuristics.
	 */
	public Jurisdiction resolveJurisdiction(String providerName, ProviderConfig config) {
		if (providerName != null) {
			Jurisdiction registered = providerJurisdictions.get(providerName.toLowerCase(Locale.ROOT));
			if (registered != null) {
				return registered;
			}
		}

		if (config != null && config.baseUrl() != null) {
			URI uri = config.baseUrl();
			String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
			String name = config.name() != null ? config.name().toLowerCase(Locale.ROOT) : "";

			if (matchesJurisdiction(host, name, "eu")) {
				return Jurisdiction.EU;
			}
			if (matchesJurisdiction(host, name, "ng")) {
				return Jurisdiction.NG;
			}
			if (matchesJurisdiction(host, name, "uk")) {
				return Jurisdiction.UK;
			}
			if (matchesJurisdiction(host, name, "ch")) {
				return Jurisdiction.CH;
			}
			if (name.contains("-us") || name.contains("_us") || name.contains("us-") || host.contains("openai.com")
					|| host.contains("anthropic.com")) {
				return Jurisdiction.US;
			}
		}

		return Jurisdiction.GLOBAL;
	}

	private static boolean matchesJurisdiction(String host, String name, String code) {
		return host.endsWith("." + code)
				|| name.contains("-" + code)
				|| name.contains("_" + code)
				|| name.contains(code + "-");
	}

	/**
	 * Filters a provider failover chain according to the active data residency policy.
	 *
	 * @param chain              candidate chain of provider references
	 * @param providers          configured provider map
	 * @param policy             enforced residency policy
	 * @param originJurisdiction origin jurisdiction of the client/tenant
	 * @param model              requested model alias (for diagnostics)
	 * @return filtered list of compliant provider references
	 * @throws DataResidencyBreachException if no compliant provider exists under strict/cascade policy
	 */
	public List<ProviderRef> filterChain(
			List<ProviderRef> chain,
			Map<String, ProviderConfig> providers,
			ResidencyPolicy policy,
			Jurisdiction originJurisdiction,
			String model
	) {
		if (chain == null || chain.isEmpty()) {
			return List.of();
		}
		if (policy == null || originJurisdiction == null || originJurisdiction == Jurisdiction.GLOBAL) {
			return chain;
		}

		List<ProviderRef> filtered = new ArrayList<>();
		for (ProviderRef ref : chain) {
			ProviderConfig config = providers.get(ref.providerName());
			Jurisdiction target = resolveJurisdiction(ref.providerName(), config);

			boolean allowed = switch (policy) {
				case STRICT_SOVEREIGN -> target == originJurisdiction || target == Jurisdiction.GLOBAL;
				case SOVEREIGN_CASCADE -> Jurisdiction.isAdequate(originJurisdiction, target);
				case PERMISSIVE_FAILOVER_WITH_AUDIT -> true;
			};

			if (allowed) {
				filtered.add(ref);
			}
		}

		if (filtered.isEmpty() && (policy == ResidencyPolicy.STRICT_SOVEREIGN
				|| policy == ResidencyPolicy.SOVEREIGN_CASCADE)) {
			throw new DataResidencyBreachException(originJurisdiction, model);
		}

		return filtered;
	}
}
