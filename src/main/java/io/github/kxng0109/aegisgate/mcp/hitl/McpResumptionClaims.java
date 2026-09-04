package io.github.kxng0109.aegisgate.mcp.hitl;

import java.time.Instant;

/**
 * Cryptographic claims embedded inside an AEAD-encrypted Human-in-the-Loop (HITL) resumption token.
 *
 * @param tokenId    unique resumption identifier
 * @param ownerId    tenant/owner binding
 * @param toolName   exact tool name being suspended
 * @param argsSha256 SHA-256 digest of original tool arguments
 * @param issuedAt   timestamp when token was minted
 * @param expiresAt  expiration deadline
 */
public record McpResumptionClaims(
		String tokenId,
		String ownerId,
		String toolName,
		String argsSha256,
		Instant issuedAt,
		Instant expiresAt
) {
	public boolean isExpired() {
		return Instant.now().isAfter(expiresAt);
	}
}
