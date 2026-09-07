package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Authenticated Encryption with Associated Data (AEAD AES-256-GCM) token minting and verification service. Enforces
 * parameter immutability, tenant binding, and strict expiration TTLs on suspended tool calls.
 */
@Slf4j
@Service
public class McpAeadResumptionTokenService {

	private static final String TOKEN_PREFIX = "v2.aead.";
	private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int GCM_IV_LENGTH_BYTES = 12;
	private static final int GCM_TAG_LENGTH_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * PBKDF2 iteration count per OWASP Password Storage Cheat Sheet and NIST SP 800-132. 600,000 iterations of
	 * HMAC-SHA-256 is the canonical minimum for FIPS-140 compliance. Java SE 25 exposes {@code PBKDF2WithHmacSHA256} as
	 * a standard {@code SecretKeyFactory} algorithm; Argon2/scrypt are not standard JDK algorithms.
	 */
	private static final int PBKDF2_ITERATIONS = 600_000;
	private static final int PBKDF2_KEY_LENGTH_BITS = 256;

	/**
	 * Fixed application-specific KDF salt. The salt need not be secret; it ensures the derived key is unique to this
	 * application and defeats precomputation. The HITL secret itself must carry the entropy (enforced at startup via
	 * {@code @Size(min = 32)} on the configuration property).
	 */
	private static final byte[] PBKDF2_SALT =
			"AegisGate-MCP-HITL-v1".getBytes(StandardCharsets.UTF_8);

	private final SecretKey aesKey;
	private final ObjectMapper objectMapper;

	public McpAeadResumptionTokenService(McpGatewayProperties properties, ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.aesKey = deriveAesKey(properties.getHitlSecret().value());
	}

	/**
	 * Mints a cryptographically signed and encrypted resumption token string.
	 *
	 * @param claims token claims
	 * @return opaque token string
	 */
	public String mintToken(McpResumptionClaims claims) {
		try {
			byte[] plaintext = objectMapper.writeValueAsBytes(claims);

			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			RANDOM.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
			GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
			cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);

			byte[] ciphertext = cipher.doFinal(plaintext);

			ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
			buffer.put(iv);
			buffer.put(ciphertext);

			String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
			return TOKEN_PREFIX + encoded;
		} catch (Exception e) {
			log.error("Failed to mint MCP AEAD resumption token: {}", e.getMessage(), e);
			throw new IllegalStateException("Failed to mint resumption token", e);
		}
	}

	/**
	 * Verifies token authenticity, AEAD integrity, expiration, owner binding, and parameter SHA-256 match.
	 *
	 * @param tokenString        candidate token string
	 * @param expectedArgsSha256 SHA-256 digest of parameters submitted on resumption
	 * @param expectedOwnerId    tenant owner ID of the caller
	 * @return verified claims if valid and authentic
	 */
	public Optional<McpResumptionClaims> verifyAndExtract(
			@Nullable String tokenString,
			String expectedArgsSha256,
			String expectedOwnerId
	) {
		if (tokenString == null || !tokenString.startsWith(TOKEN_PREFIX)) {
			return Optional.empty();
		}
		try {
			String b64 = tokenString.substring(TOKEN_PREFIX.length());
			byte[] payload = Base64.getUrlDecoder().decode(b64);
			if (payload.length <= GCM_IV_LENGTH_BYTES + 16) {
				return Optional.empty();
			}

			ByteBuffer buffer = ByteBuffer.wrap(payload);
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			buffer.get(iv);

			byte[] ciphertext = new byte[buffer.remaining()];
			buffer.get(ciphertext);

			Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
			GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
			cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);

			byte[] plaintext = cipher.doFinal(ciphertext);
			McpResumptionClaims claims = objectMapper.readValue(plaintext, McpResumptionClaims.class);

			// 1. Expiration check
			if (claims.isExpired()) {
				log.warn("Resumption token '{}' has expired (expiredAt: {})", claims.tokenId(), claims.expiresAt());
				return Optional.empty();
			}

			// 2. Tenant owner binding check
			if (!claims.ownerId().equals(expectedOwnerId)) {
				log.warn("Resumption token owner mismatch: expected '{}', got '{}'", expectedOwnerId, claims.ownerId());
				return Optional.empty();
			}

			// 3. Parameter SHA-256 digest match check (prevents argument tampering after approval)
			if (!claims.argsSha256().equalsIgnoreCase(expectedArgsSha256)) {
				log.warn("Resumption token argument tamper detected: digest mismatch");
				return Optional.empty();
			}

			return Optional.of(claims);
		} catch (Exception e) {
			log.warn("Failed to decrypt or verify MCP resumption token: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Computes the SHA-256 hex digest of serialized tool arguments for tamper-proofing.
	 */
	public static String computeArgsSha256(@Nullable String serializedArgs) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] bytes = (serializedArgs == null ? "" : serializedArgs).getBytes(StandardCharsets.UTF_8);
			byte[] digest = md.digest(bytes);
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/**
	 * Derives an AES-256 key from the HITL secret using PBKDF2-HMAC-SHA256 with 600,000 iterations.
	 *
	 * <p>This replaces the previous single-pass SHA-256 derivation, which was a fast hash with no computational
	 * cost against brute-force attacks (CWE-916). PBKDF2 is the NIST SP 800-132 canonical choice for key
	 * derivation and is FIPS-140 validated. Called once at construction; zero per-request cost.</p>
	 *
	 * @param secret the raw secret string from the runtime environment
	 * @return a 256-bit AES key
	 */
	private static SecretKey deriveAesKey(String secret) {
		char[] password = secret.toCharArray();
		PBEKeySpec spec = new PBEKeySpec(password, PBKDF2_SALT, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS);
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			byte[] keyBytes = factory.generateSecret(spec).getEncoded();
			try {
				return new SecretKeySpec(keyBytes, "AES");
			} finally {
				Arrays.fill(keyBytes, (byte) 0);
			}
		} catch (Exception e) {
			throw new IllegalStateException("PBKDF2 key derivation failed", e);
		} finally {
			spec.clearPassword();
			Arrays.fill(password, '\0');
		}
	}
}
