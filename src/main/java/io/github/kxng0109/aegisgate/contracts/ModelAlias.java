package io.github.kxng0109.aegisgate.contracts;

import java.util.List;

/**
 * The routing plan behind a client facing model name: an ordered chain of
 * provider steps and the strategy used to walk it.
 *
 * <p>Bound from {@code gateway.aliases.<model-name>} in application
 * configuration. The client always requests a model name; the gateway resolves
 * it to an alias, which carries the chain and the strategy.</p>
 *
 * @param chain    ordered provider steps to try
 * @param strategy how the chain is walked (sequential or race)
 */
public record ModelAlias(
		List<ProviderRef> chain,
		FailoverStrategy strategy
) {
}