package io.github.kxng0109.aegisgate.proxy;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Gateway-terminated idempotency keys (Stripe-compatible semantics).
 *
 * <p>A client sends one opaque {@code Idempotency-Key} per logical operation and reuses it on every retry. The
 * gateway derives a deterministic version-5 request UUID from tenant, route, key, and body fingerprint, so a retried
 * request carries the <em>same</em> usage-ledger id on every instance and every attempt. The ledger's
 * {@code request_id} uniqueness constraint then turns a duplicate delivery into a benign no-op instead of a double
 * charge. This is <em>effectively-once</em> delivery: true exactly-once is impossible over networks, and is not
 * claimed.</p>
 *
 * <p>Rules: absent or blank header means "no idempotency requested" (random UUID, unchanged behavior). A present but
 * malformed header (over 255 chars or outside printable ASCII) is rejected by the caller with HTTP 400 — silent
 * acceptance would let a client believe retries are safe when they are not. Keys are never logged and never
 * forwarded to upstream providers (a provider could misinterpret our client key as its own idempotency scope).</p>
 */
public final class IdempotencyKeys {

	/** Request header carrying the client-minted idempotency key. */
	public static final String HEADER = "Idempotency-Key";

	/** Maximum accepted key length in characters (Stripe-compatible bound). */
	public static final int MAX_KEY_LENGTH = 255;

	/**
	 * Fixed version-1 namespace for request-UUID derivation. Baked in (never rotated): changing it would orphan
	 * every in-flight retry key. A future derivation change must mint a new namespace constant instead.
	 */
	static final UUID NAMESPACE_V1 = UUID.fromString("0e0b6a5c-4d3e-5f60-8000-000000000a17");

	private IdempotencyKeys() {
	}

	/**
	 * Validates a raw header value.
	 *
	 * @param rawKey the header value, possibly {@code null}
	 * @return the trimmed key, or {@code null} when absent or blank (no idempotency requested)
	 * @throws IllegalArgumentException when present but malformed (too long or non-printable ASCII)
	 */
	public static @Nullable String validateOrNull(@Nullable String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			return null;
		}
		String key = rawKey.trim();
		if (key.length() > MAX_KEY_LENGTH || !isPrintableAscii(key)) {
			throw new IllegalArgumentException(
					"Idempotency-Key must be 1-255 printable ASCII characters");
		}
		return key;
	}

	/**
	 * Resolves the usage-ledger request UUID: deterministic version-5 when the client supplied a valid key,
	 * random otherwise (preserving pre-idempotency behavior exactly).
	 *
	 * @param rawKey   the raw header value, possibly {@code null}
	 * @param ownerId  authenticated tenant/owner (empty string when unknown)
	 * @param route    request route (for example the servlet path)
	 * @param bodyHash SHA-256 hex of the canonical request bytes the retry must match byte-for-byte
	 * @return the request UUID to record
	 * @throws IllegalArgumentException when the key is present but malformed
	 */
	public static UUID resolveRequestId(
			@Nullable String rawKey, String ownerId, String route, String bodyHash) {
		String key = validateOrNull(rawKey);
		if (key == null) {
			return UUID.randomUUID();
		}
		return deriveV5(ownerId + "\n" + route + "\n" + key + "\n" + bodyHash);
	}

	/**
	 * Derives a RFC 4122 version-5 (SHA-1 name-based) UUID in the {@link #NAMESPACE_V1} namespace.
	 * Implemented by hand because the JDK ships MD5-based version 3 only.
	 */
	static UUID deriveV5(String name) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(toBytes(NAMESPACE_V1));
			byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
			hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
			hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
			long msb = 0;
			long lsb = 0;
			for (int i = 0; i < 8; i++) {
				msb = (msb << 8) | (hash[i] & 0xff);
			}
			for (int i = 8; i < 16; i++) {
				lsb = (lsb << 8) | (hash[i] & 0xff);
			}
			return new UUID(msb, lsb);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 must always be available", e);
		}
	}

	/** SHA-256 hex of raw bytes, for body fingerprints. */
	public static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			byte[] digest = sha256.digest(bytes);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16));
				hex.append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 must always be available", e);
		}
	}

	private static boolean isPrintableAscii(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x21 || c > 0x7e) {
				return false;
			}
		}
		return true;
	}

	private static byte[] toBytes(UUID uuid) {
		long msb = uuid.getMostSignificantBits();
		long lsb = uuid.getLeastSignificantBits();
		byte[] out = new byte[16];
		for (int i = 0; i < 8; i++) {
			out[i] = (byte) (msb >>> (8 * (7 - i)));
			out[15 - i] = (byte) (lsb >>> (8 * i));
		}
		return out;
	}
}
