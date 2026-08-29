package io.github.kxng0109.aegisgate.contracts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway configuration bound from the {@code gateway} prefix.
 *
 * <p>Owns three things:</p>
 * <ul>
 *   <li>{@code providers} — the pool of configured upstream providers, keyed
 *       by name.</li>
 *   <li>{@code aliases} — the routing plans behind each client facing model
 *       name.</li>
 *   <li>{@code bootstrapKeys} — virtual API keys seeded into Redis at startup
 *       so requests can be authenticated before an admin API exists. Plaintext
 *       keys must only ever be injected via environment variables (never
 *       committed to the repository).</li>
 * </ul>
 */
@ConfigurationProperties("gateway")
public class GatewayProperties {

	private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

	private Map<String, ModelAlias> aliases = new LinkedHashMap<>();

	private List<BootstrapKey> bootstrapKeys = new ArrayList<>();

	/**
	 * @return the configured providers, keyed by {@link ProviderConfig#name()}
	 */
	public Map<String, ProviderConfig> getProviders() {
		return providers;
	}

	/**
	 * @param providers providers to serve
	 */
	public void setProviders(Map<String, ProviderConfig> providers) {
		this.providers = providers;
	}

	/**
	 * @return the configured aliases, keyed by client facing model name
	 */
	public Map<String, ModelAlias> getAliases() {
		return aliases;
	}

	/**
	 * @param aliases aliases to serve
	 */
	public void setAliases(Map<String, ModelAlias> aliases) {
		this.aliases = aliases;
	}

	/**
	 * @return the configured bootstrap keys (empty when none are configured)
	 */
	public List<BootstrapKey> getBootstrapKeys() {
		return bootstrapKeys;
	}

	/**
	 * @param bootstrapKeys bootstrap keys to seed at startup
	 */
	public void setBootstrapKeys(List<BootstrapKey> bootstrapKeys) {
		this.bootstrapKeys = bootstrapKeys;
	}
}