package io.github.kxng0109.aegisgate.contracts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Gateway configuration bound from the {@code gateway} prefix.
 *
 * <p>Phase 2 owns the {@code bootstrap-keys} list: virtual API keys seeded into
 * Redis at startup so that requests can be authenticated before an admin API
 * exists. Plaintext keys must only ever be injected via environment variables
 * (never committed to the repository).</p>
 */
@ConfigurationProperties("gateway")
public class GatewayProperties {

	private List<BootstrapKey> bootstrapKeys = new ArrayList<>();

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