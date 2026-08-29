package io.github.kxng0109.aegisgate.contracts;

/**
 * How a {@link ModelAlias} treats its provider chain.
 *
 * <p>{@link #SEQUENTIAL} tries providers in order and stops at the first
 * success; {@link #RACE} fires all providers concurrently and streams the
 * first successful response, cancelling the losers.</p>
 */
public enum FailoverStrategy {

	/**
	 * Try each {@link ProviderRef} in the chain, one after another, stopping
	 * at the first successful response.
	 */
	SEQUENTIAL,

	/**
	 * Fire every provider at once; the first successful response wins and all
	 * other in-flight attempts are cancelled.
	 */
	RACE
}