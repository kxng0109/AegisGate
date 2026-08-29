package io.github.kxng0109.aegisgate.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hash of a virtual API key's plaintext. The raw key is never stored; only this hex digest is persisted (as the
 * Redis key) and used for lookups.
 *
 * <p>{@code toString()} is masked so the digest cannot leak into logs.</p>
 */
public final class SHA256Hash {

	private final String hex;

	private SHA256Hash(String hex) {
		this.hex = hex;
	}

	/**
	 * Hashes a raw key using SHA-256, returning the hex digest.
	 *
	 * @param rawKey plaintext API key (e.g. {@code gw-...}); must not be null
	 * @return the hex-encoded SHA-256 digest
	 */
	public static SHA256Hash fromRawKey(String rawKey) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(rawKey.getBytes(StandardCharsets.UTF_8));
			return new SHA256Hash(HexFormat.of().formatHex(digest));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 algorithm unavailable", e);
		}
	}

	/**
	 * Wraps an already-computed hex digest (used when reconstructing from Redis).
	 *
	 * @param hex hex SHA-256 string
	 * @return hash instance
	 */
	public static SHA256Hash fromHex(String hex) {
		return new SHA256Hash(hex);
	}

	public String hex() {
		return hex;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof SHA256Hash other && hex.equals(other.hex);
	}

	@Override
	public int hashCode() {
		return hex.hashCode();
	}

	@Override
	public String toString() {
		return "****";
	}
}
