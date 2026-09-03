package io.github.kxng0109.aegisgate.security.compliance;

/**
 * Enterprise data residency and geo-sovereignty policy modes.
 */
public enum ResidencyPolicy {
	/**
	 * Hard boundary: inference and caching are strictly confined to the origin jurisdiction. If all in-region providers
	 * are unavailable, fails closed immediately with HTTP 503.
	 */
	STRICT_SOVEREIGN,

	/**
	 * Cascade fallback: failover is permitted exclusively to whitelisted jurisdictions possessing recognized adequacy
	 * decisions (GDPR Art. 45, NDPA 2023 Sec. 41-43).
	 */
	SOVEREIGN_CASCADE,

	/**
	 * High availability with audit: cross-border failover is permitted globally, emitting an Ed25519/HMAC-signed
	 * cryptographic audit receipt and compliance headers.
	 */
	PERMISSIVE_FAILOVER_WITH_AUDIT
}
