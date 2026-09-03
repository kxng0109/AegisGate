package io.github.kxng0109.aegisgate.security.compliance;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, forward-secure cryptographic audit ledger and binary Merkle chain.
 *
 * <p>Produces non-repudiation cryptographic receipts (\(X\text{-}Aegis\text{-}Audit\text{-}Receipt\))
 * by linking request metadata, prompt hash, response hash, and forward-secure hash state. Uses atomic references for
 * lock-free concurrency, ensuring zero carrier thread pinning.</p>
 */
@Component
public class MerkleAuditLedger {

	private static final String HMAC_ALGO = "HmacSHA256";
	private static final byte[] GENESIS_CHAIN_STATE = new byte[32];

	static {
		new SecureRandom().nextBytes(GENESIS_CHAIN_STATE);
	}

	private final byte[] hmacKeyBytes = new byte[32];
	private final AtomicReference<byte[]> cumulativeChainState = new AtomicReference<>(GENESIS_CHAIN_STATE.clone());

	public MerkleAuditLedger() {
		new SecureRandom().nextBytes(hmacKeyBytes);
	}

	/**
	 * Records a proxy transaction and generates a forward-secure cryptographic receipt.
	 *
	 * @param tenantId     tenant or owner ID
	 * @param keyHash      hashed virtual key identifier
	 * @param promptBytes  raw bytes of the prompt payload
	 * @param responseHash SHA-256 hash of the generated response
	 * @return non-repudiation cryptographic audit receipt
	 */
	public AuditReceipt recordTransaction(
			String tenantId,
			String keyHash,
			byte[] promptBytes,
			String responseHash
	) {
		long timestamp = Instant.now().toEpochMilli();
		byte[] promptHash = sha256(promptBytes != null ? promptBytes : new byte[0]);

		// 1. Calculate Leaf = SHA-256(Timestamp || TenantId || KeyHash || H(Prompt) || H(Response))
		MessageDigest digest = getSha256Digest();
		digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update((tenantId != null ? tenantId : "anonymous").getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update((keyHash != null ? keyHash : "unauthenticated").getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update(promptHash);
		digest.update((byte) ':');
		digest.update((responseHash != null ? responseHash : "none").getBytes(StandardCharsets.UTF_8));
		byte[] leafBytes = digest.digest();

		// 2. Advance Forward-Secure Hash Chain: Chain_i = SHA-256(Chain_{i-1} || Leaf_i)
		byte[] newChainState;
		while (true) {
			byte[] currentChain = cumulativeChainState.get();
			MessageDigest chainDigest = getSha256Digest();
			chainDigest.update(currentChain);
			chainDigest.update(leafBytes);
			newChainState = chainDigest.digest();
			if (cumulativeChainState.compareAndSet(currentChain, newChainState)) {
				break;
			}
		}

		// 3. HMAC signature over Leaf and Chain state
		String leafHex = HexFormat.of().formatHex(leafBytes);
		String chainHex = HexFormat.of().formatHex(newChainState);
		String signatureHex = computeHmacHex(leafHex + ":" + chainHex);

		String receiptHeader =
				leafHex.substring(0, 16) + ":" + chainHex.substring(0, 16) + ":" + signatureHex.substring(0, 16);

		return new AuditReceipt(receiptHeader, leafHex, chainHex, signatureHex);
	}

	private String computeHmacHex(String data) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(hmacKeyBytes, HMAC_ALGO));
			byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hmacBytes);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to calculate HMAC signature", e);
		}
	}

	private static byte[] sha256(byte[] data) {
		return getSha256Digest().digest(data);
	}

	private static MessageDigest getSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	public record AuditReceipt(
			String receiptHeaderValue,
			String leafHash,
			String chainHash,
			String signature
	) {
	}
}
