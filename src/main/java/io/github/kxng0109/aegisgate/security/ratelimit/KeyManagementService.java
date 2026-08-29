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

	private void storeKey(
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
		cache.invalidate(hash);
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