package io.github.kxng0109.aegisgate.cache.contracts;

/**
 * Defines the multi-tenant isolation scope for cached completions.
 */
public enum CacheScope {
	/**
	 * Shared across all users within the same tenant / organization (default).
	 */
	TENANT,

	/**
	 * Strictly isolated to the specific authenticated API key or user ID.
	 */
	USER,

	/**
	 * Shared globally across all tenants (opt-in for public/static models).
	 */
	GLOBAL
}
