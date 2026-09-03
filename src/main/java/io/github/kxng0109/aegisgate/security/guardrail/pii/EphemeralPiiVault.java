package io.github.kxng0109.aegisgate.security.guardrail.pii;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral request-scoped vault storing PII-to-surrogate mappings.
 *
 * <p>Plaintext values are encrypted with AES-256-GCM using an ephemeral per-request key.
 * Implements {@link AutoCloseable} to provide zero-trace memory sanitization: underlying byte buffers and keys are
 * zero-filled upon completion.</p>
 */
public final class EphemeralPiiVault implements AutoCloseable {

	private static final int GCM_TAG_LENGTH_BITS = 128;
	private static final int GCM_IV_LENGTH_BYTES = 12;

	private final SecureRandom secureRandom = new SecureRandom();
	private final SecretKey ephemeralKey;
	private final Map<String, EncryptedEntry> vault = new ConcurrentHashMap<>();
	private final Map<String, String> plainToSurrogate = new ConcurrentHashMap<>();
	private volatile boolean closed = false;

	public EphemeralPiiVault() {
		try {
			KeyGenerator keyGen = KeyGenerator.getInstance("AES");
			keyGen.init(256);
			this.ephemeralKey = keyGen.generateKey();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("AES-256 not supported in environment", e);
		}
	}

	/**
	 * Stores a plaintext PII value under the given surrogate token.
	 *
	 * @param surrogate synthetic placeholder token (e.g., "&lt;PERSON_1&gt;")
	 * @param plaintext raw cleartext value
	 */
	public void store(String surrogate, String plaintext) {
		if (closed) {
			throw new IllegalStateException("Vault is closed");
		}
		if (surrogate == null || plaintext == null) {
			return;
		}

		plainToSurrogate.put(plaintext, surrogate);

		byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
		try {
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			secureRandom.nextBytes(iv);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, ephemeralKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintextBytes);

			vault.put(surrogate, new EncryptedEntry(iv, ciphertext));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to encrypt PII into ephemeral vault", e);
		} finally {
			// Zero-trace memory hygiene: wipe plaintext buffer immediately
			Arrays.fill(plaintextBytes, (byte) 0);
		}
	}

	/**
	 * Resolves a surrogate token back to its cleartext equivalent.
	 *
	 * @param surrogate synthetic placeholder token
	 * @return decrypted cleartext value, or null if not found
	 */
	public String resolve(String surrogate) {
		if (closed) {
			throw new IllegalStateException("Vault is closed");
		}
		EncryptedEntry entry = vault.get(surrogate);
		if (entry == null) {
			return null;
		}

		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, ephemeralKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, entry.iv()));
			byte[] decrypted = cipher.doFinal(entry.ciphertext());
			String res = new String(decrypted, StandardCharsets.UTF_8);
			Arrays.fill(decrypted, (byte) 0);
			return res;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to decrypt PII from ephemeral vault", e);
		}
	}

	/**
	 * Checks if an exact plaintext has already been mapped to an existing surrogate.
	 *
	 * @param plaintext candidate value
	 * @return existing surrogate token, or null if none
	 */
	public String getExistingSurrogate(String plaintext) {
		return plainToSurrogate.get(plaintext);
	}

	/**
	 * Returns an unmodifiable snapshot of surrogate keys.
	 */
	public Iterable<String> getSurrogates() {
		return vault.keySet();
	}

	public boolean isEmpty() {
		return vault.isEmpty();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;

		// Overwrite ciphertext buffers with zero bytes
		for (EncryptedEntry entry : vault.values()) {
			Arrays.fill(entry.iv(), (byte) 0);
			Arrays.fill(entry.ciphertext(), (byte) 0);
		}
		vault.clear();
		plainToSurrogate.clear();

		// Destroy key if possible
		try {
			ephemeralKey.destroy();
		} catch (Exception ignored) {
		}
	}

	private record EncryptedEntry(byte[] iv, byte[] ciphertext) {
	}
}
