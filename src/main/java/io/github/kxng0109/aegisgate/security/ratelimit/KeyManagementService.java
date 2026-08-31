package io.github.kxng0109.aegisgate.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aegisgate.contracts.BootstrapKey;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lifecycle and lookup for gateway-managed virtual API keys.
 *
 * <p>Keys are identified by the SHA-256 digest of their plaintext. The plaintext is
 * generated once (for {@link #generateKey(BootstrapKey)}) or supplied by configuration (bootstrap keys) and is never
 * persisted: only its digest and metadata are stored in a Redis hash under {@code apikey:{hex}}. A short-TTL Caffeine
 * cache absorbs hot request-path lookups, including confirmed misses.</p>
 *
 * <p>Boot-time seeding of configured keys is owned by {@link BootstrapKeySeeder}; this
 * service only provides the idempotent {@link #seedBootstrapKeys(GatewayProperties)} operation the seeder invokes. The
 * request path is fail-closed: when Redis is unreachable, {@link #findByHash(SHA256Hash)} lets the underlying
 * {@link org.springframework.dao.DataAccessException} (or connection-pool exception) propagate to the caller, which
 * maps it to HTTP 503. A key is never silently treated as absent merely because the backend was down.</p>
 */
@Service
@RequiredArgsConstructor
public class KeyManagementService {

	private static final String REDIS_KEY_PREFIX = "apikey:";
	private static final String INDEX_KEY = "admin:keys";
	private static final String KEY_PREFIX_RAW = "gw-";
	private static final int RANDOM_SUFFIX_LENGTH = 32;
	private static final char[] URL_SAFE_ALPHABET =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".toCharArray();

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final StringRedisTemplate redisTemplate;

	/**
	 * Short-TTL local cache of resolved keys, holding {@link Optional}s so a confirmed miss is cached without colliding
	 * with an in-flight load. Exceptions thrown by the loader are never cached: they propagate to the caller (fail
	 * closed).
	 */
	private final Cache<SHA256Hash, Optional<VirtualApiKey>> cache = Caffeine.newBuilder()
	                                                                         .expireAfterWrite(Duration.ofSeconds(5))
	                                                                         .maximumSize(1000)
	                                                                         .build();

	private static String randomPlaintext() {
		StringBuilder sb = new StringBuilder(KEY_PREFIX_RAW);
		for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
			sb.append(URL_SAFE_ALPHABET[SECURE_RANDOM.nextInt(URL_SAFE_ALPHABET.length)]);
		}
		return sb.toString();
	}

	private static String prefixOf(String plaintextKey) {
		int idx = plaintextKey.indexOf('-');
		return idx > 0 ? plaintextKey.substring(0, idx + 1) : plaintextKey;
	}

	private static String toCsv(Set<String> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}
		return String.join(",", values);
	}

	private static Set<String> parseCsv(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(csv.split(","))
		             .map(String::trim)
		             .filter(s -> !s.isEmpty())
		             .collect(Collectors.toUnmodifiableSet());
	}

	private static String redisKey(SHA256Hash hash) {
		return REDIS_KEY_PREFIX + hash.hex();
	}

	/**
	 * Value object returned only upon key generation, pairing the cryptographic hash with the single-exposure
	 * plaintext.
	 *
	 * @param hash         SHA-256 digest of the key
	 * @param plaintextKey plaintext token (shown only once)
	 * @param key          stored key metadata
	 */
	public record CreatedKey(SHA256Hash hash, String plaintextKey, VirtualApiKey key) {
	}

	/**
	 * Generates a brand-new virtual API key from a {@link BootstrapKey} template and stores only its metadata hash in
	 * Redis.
	 *
	 * @param template key parameters (owner, label, limits, model/provider allow-lists)
	 * @return the plaintext key ({@code gw-} + 32 URL-safe characters); this is the only time the plaintext exists and
	 * it is never logged or stored
	 */
	public String generateKey(BootstrapKey template) {
		String plaintext = randomPlaintext();
		storeKey(
				plaintext,
				template.ownerId(),
				template.name(),
				template.rpmLimit(),
				template.tpmLimit(),
				template.allowedModels(),
				template.allowedProviders()
		);
		return plaintext;
	}

	/**
	 * Creates a new virtual API key with the given parameters, persists it in Redis, adds it to the admin index, and
	 * returns the single-exposure plaintext along with metadata.
	 *
	 * @param ownerId          owner identifier
	 * @param name             label for the key
	 * @param rpmLimit         requests per minute limit (0 = unlimited)
	 * @param tpmLimit         tokens per minute limit (0 = unlimited)
	 * @param allowedModels    allowed model names (empty = all)
	 * @param allowedProviders allowed provider names (empty = all)
	 * @return the created key object containing the plaintext and metadata
	 */
	public CreatedKey createKey(
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders
	) {
		String plaintext = randomPlaintext();
		Instant now = Instant.now();
		SHA256Hash hash = SHA256Hash.fromRawKey(plaintext);
		String keyPrefix = prefixOf(plaintext);
		VirtualApiKey metadata = new VirtualApiKey(
				hash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				true,
				now
		);
		storeKey(plaintext, ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders);
		return new CreatedKey(hash, plaintext, metadata);
	}

	/**
	 * Lists all virtual API keys registered in the gateway, optionally filtered by owner ID.
	 *
	 * @param ownerId optional owner ID to filter by; if null or blank, returns all keys
	 * @return list of virtual API keys sorted by creation time descending
	 */
	public List<VirtualApiKey> listKeys(String ownerId) {
		Set<String> hexes = redisTemplate.opsForSet().members(INDEX_KEY);
		if (hexes == null || hexes.isEmpty()) {
			return List.of();
		}
		List<VirtualApiKey> keys = new ArrayList<>();
		for (String hex : hexes) {
			try {
				SHA256Hash hash = SHA256Hash.fromHex(hex);
				findByHash(hash).ifPresent(key -> {
					if (ownerId == null || ownerId.isBlank() || ownerId.equals(key.ownerId())) {
						keys.add(key);
					}
				});
			} catch (IllegalArgumentException ignored) {
				// skip invalid hex entry in index
			}
		}
		keys.sort(Comparator.comparing(VirtualApiKey::createdAt).reversed());
		return Collections.unmodifiableList(keys);
	}

	/**
	 * Updates an existing key's metadata, invalidating the local cache.
	 *
	 * @param hash             key hash to update
	 * @param name             new name (or null to keep)
	 * @param rpmLimit         new RPM limit (or null to keep)
	 * @param tpmLimit         new TPM limit (or null to keep)
	 * @param allowedModels    new allowed models (or null to keep)
	 * @param allowedProviders new allowed providers (or null to keep)
	 * @param enabled          new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Boolean enabled
	) {
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
		}
		Map<String, String> updates = new LinkedHashMap<>();
		if (name != null) {
			updates.put("name", name);
		}
		if (rpmLimit != null) {
			updates.put("rpmLimit", Integer.toString(rpmLimit));
		}
		if (tpmLimit != null) {
			updates.put("tpmLimit", Integer.toString(tpmLimit));
		}
		if (allowedModels != null) {
			updates.put("allowedModels", toCsv(allowedModels));
		}
		if (allowedProviders != null) {
			updates.put("allowedProviders", toCsv(allowedProviders));
		}
		if (enabled != null) {
			updates.put("enabled", enabled.toString());
		}
		if (!updates.isEmpty()) {
			redisTemplate.opsForHash().putAll(key, updates);
			cache.invalidate(hash);
		}
		return findByHash(hash);
	}

	/**
	 * Permanently deletes a virtual API key from Redis and removes it from the index set.
	 *
	 * @param hash key hash to delete
	 * @return true if key was deleted, false if not found
	 */
	public boolean deleteKey(SHA256Hash hash) {
		String key = redisKey(hash);
		Boolean deleted = redisTemplate.delete(key);
		redisTemplate.opsForSet().remove(INDEX_KEY, hash.hex());
		cache.invalidate(hash);
		return Boolean.TRUE.equals(deleted);
	}

	/**
	 * Disables a key by flipping its {@code enabled} flag in Redis and evicting the local cache entry so the next
	 * lookup observes the revocation.
	 *
	 * @param hash key hash to revoke
	 */
	public void revokeKey(SHA256Hash hash) {
		redisTemplate.opsForHash().put(redisKey(hash), "enabled", "false");
		cache.invalidate(hash);
	}

	/**
	 * Resolves a key by its hash, using the local short-TTL cache first.
	 *
	 * <p>Fail-closed: a Redis outage ({@link org.springframework.dao.DataAccessException}
	 * or connection-pool exception) is <em>not</em> swallowed here  -  it propagates to the caller (which maps it to
	 * HTTP 503) and is never cached. Only malformed or incomplete stored data degrades to an empty result, and a
	 * confirmed miss is negatively cached for the TTL.</p>
	 *
	 * @param hash key hash
	 * @return the key if present and parsable, otherwise empty
	 */
	public Optional<VirtualApiKey> findByHash(SHA256Hash hash) {
		return cache.get(hash, this::loadFromRedis);
	}

	/**
	 * Seeds configured bootstrap keys so they exist at runtime. Idempotent: entries with a null or blank
	 * {@code plaintextKey} are skipped, and a key whose hash is already present is never overwritten. Never logs
	 * plaintexts.
	 *
	 * <p>Fail-closed: a Redis failure propagates to the caller; {@link BootstrapKeySeeder}
	 * catches it and defers seeding to its scheduled retry.</p>
	 *
	 * @param properties gateway configuration providing the bootstrap keys
	 */
	public void seedBootstrapKeys(GatewayProperties properties) {
		for (BootstrapKey bootstrapKey : properties.getBootstrapKeys()) {
			if (bootstrapKey.plaintextKey() == null || bootstrapKey.plaintextKey().isBlank()) {
				continue;
			}
			SHA256Hash hash = SHA256Hash.fromRawKey(bootstrapKey.plaintextKey());
			if (findByHash(hash).isEmpty()) {
				storeKey(
						bootstrapKey.plaintextKey(),
						bootstrapKey.ownerId(),
						bootstrapKey.name(),
						bootstrapKey.rpmLimit(),
						bootstrapKey.tpmLimit(),
						bootstrapKey.allowedModels(),
						bootstrapKey.allowedProviders()
				);
			}
		}
	}

	private SHA256Hash storeKey(
			String plaintextKey,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders
	) {
		SHA256Hash hash = SHA256Hash.fromRawKey(plaintextKey);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", ownerId);
		fields.put("name", name);
		fields.put("rpmLimit", Integer.toString(rpmLimit));
		fields.put("tpmLimit", Integer.toString(tpmLimit));
		fields.put("enabled", "true");
		fields.put("allowedModels", toCsv(allowedModels));
		fields.put("allowedProviders", toCsv(allowedProviders));
		fields.put("createdAt", Instant.now().toString());
		fields.put("keyPrefix", prefixOf(plaintextKey));
		redisTemplate.opsForHash().putAll(redisKey(hash), fields);
		redisTemplate.opsForSet().add(INDEX_KEY, hash.hex());
		cache.invalidate(hash);
		return hash;
	}

	private Optional<VirtualApiKey> loadFromRedis(SHA256Hash hash) {
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
		}
		Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
		if (raw.isEmpty()) {
			return Optional.empty();
		}
		// Redis access stays outside the try: connectivity failures must propagate
		// (fail closed). Only malformed or incomplete stored data degrades to a miss.
		try {
			String ownerId = (String) raw.get("ownerId");
			String name = (String) raw.get("name");
			int rpmLimit = Integer.parseInt((String) raw.get("rpmLimit"));
			int tpmLimit = Integer.parseInt((String) raw.get("tpmLimit"));
			boolean enabled = Boolean.parseBoolean((String) raw.get("enabled"));
			Set<String> allowedModels = parseCsv((String) raw.get("allowedModels"));
			Set<String> allowedProviders = parseCsv((String) raw.get("allowedProviders"));
			Instant createdAt = Instant.parse((String) raw.get("createdAt"));
			String keyPrefix = (String) raw.getOrDefault("keyPrefix", KEY_PREFIX_RAW);
			return Optional.of(new VirtualApiKey(
					hash,
					keyPrefix,
					ownerId,
					name,
					rpmLimit,
					tpmLimit,
					allowedModels,
					allowedProviders,
					enabled,
					createdAt
			));
		} catch (RuntimeException ignored) {
			// Malformed or incomplete stored metadata: treat as absent, never throw.
			return Optional.empty();
		}
	}
}