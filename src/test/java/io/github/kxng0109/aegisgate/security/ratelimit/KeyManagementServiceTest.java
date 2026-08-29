package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.BootstrapKey;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeyManagementServiceTest {

	private static final String URL_SAFE_ALPHABET =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
	private static final String FIXED_PLAINTEXT = "gw-abcdefghijklmnopqrstuvwxyz012345";
	private static final String CREATED_AT = "2026-08-29T10:00:00Z";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		return new KeyManagementService(redisTemplate);
	}

	private void stubPresent(SHA256Hash hash, Map<String, String> entries) {
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(entries);
	}

	private static Map<String, String> fields(
			String ownerId,
			String name,
			String rpmLimit,
			String tpmLimit,
			String enabled,
			String allowedModels,
			String allowedProviders,
			String createdAt,
			String keyPrefix
	) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", ownerId);
		fields.put("name", name);
		fields.put("rpmLimit", rpmLimit);
		fields.put("tpmLimit", tpmLimit);
		fields.put("enabled", enabled);
		fields.put("allowedModels", allowedModels);
		fields.put("allowedProviders", allowedProviders);
		fields.put("createdAt", createdAt);
		fields.put("keyPrefix", keyPrefix);
		return fields;
	}

	private static SHA256Hash hashOf(String plaintext) {
		return SHA256Hash.fromRawKey(plaintext);
	}

	private static String redisKey(SHA256Hash hash) {
		return "apikey:" + hash.hex();
	}

	@Test
	void generateKeyReturnsGwPrefixedPlaintextOfExactAlphabet() {
		KeyManagementService service = newService();

		String plaintext = service.generateKey(
				new BootstrapKey("owner", "name", "ignored", 5, 50, Set.of("a", "b"), Set.of("c")));

		assertTrue(plaintext.startsWith("gw-"));
		assertEquals(35, plaintext.length());
		String suffix = plaintext.substring("gw-".length());
		assertEquals(32, suffix.length());
		for (int i = 0; i < suffix.length(); i++) {
			assertTrue(
					URL_SAFE_ALPHABET.indexOf(suffix.charAt(i)) >= 0,
					"character not in URL-safe alphabet at index " + i
			);
		}
	}

	@Test
	void generateKeyStoresHashOnlyAndNeverThePlaintext() {
		KeyManagementService service = newService();

		String plaintext = service.generateKey(
				new BootstrapKey("owner", "name", "ignored", 5, 50, Set.of(), Set.of()));

		String redisKey = redisKey(hashOf(plaintext));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey), fieldsCaptor.capture());

		Map<String, String> stored = fieldsCaptor.getValue();
		assertFalse(stored.containsKey(plaintext));
		assertFalse(stored.containsValue(plaintext));
		for (String value : stored.values()) {
			assertFalse(value != null && value.contains(plaintext), "stored field contains the plaintext key");
		}
		assertEquals("owner", stored.get("ownerId"));
		assertEquals("name", stored.get("name"));
		assertEquals("5", stored.get("rpmLimit"));
		assertEquals("50", stored.get("tpmLimit"));
		assertEquals("true", stored.get("enabled"));
		assertEquals("", stored.get("allowedModels"));
		assertEquals("", stored.get("allowedProviders"));
		assertEquals("gw-", stored.get("keyPrefix"));
		assertNotNull(stored.get("createdAt"));
	}

	@Test
	void revokeKeyDisablesKeyAndInvalidatesCache() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"),
				fields("owner", "name", "5", "50", "false", "", "", CREATED_AT, "gw-")
		);

		Optional<VirtualApiKey> before = service.findByHash(hash);
		service.revokeKey(hash);
		Optional<VirtualApiKey> after = service.findByHash(hash);

		verify(hashOps).put(redisKey(hash), "enabled", "false");
		verify(redisTemplate, times(2)).hasKey(redisKey(hash));
		assertTrue(before.isPresent());
		assertTrue(before.get().enabled());
		assertTrue(after.isPresent());
		assertFalse(after.get().enabled());
	}

	@Test
	void findByHashCachesWithinTtl() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubPresent(hash, fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> first = service.findByHash(hash);
		Optional<VirtualApiKey> second = service.findByHash(hash);

		assertTrue(first.isPresent());
		assertSame(first, second);
		verify(redisTemplate, times(1)).hasKey(redisKey(hash));
	}

	@Test
	void findByHashCachesNegativeResults() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-missing");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.FALSE);

		Optional<VirtualApiKey> first = service.findByHash(hash);
		Optional<VirtualApiKey> second = service.findByHash(hash);

		assertTrue(first.isEmpty());
		assertTrue(second.isEmpty());
		assertSame(first, second);
		verify(redisTemplate, times(1)).hasKey(redisKey(hash));
	}

	@Test
	void findByHashRoundTripsEveryVirtualApiKeyField() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Instant createdAt = Instant.parse(CREATED_AT);
		stubPresent(
				hash, fields(
						"owner-7", "prod-key", "120", "9000", "true",
						"gpt-4,gpt-4o", "openai,anthropic", createdAt.toString(), "gw-"
				)
		);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		VirtualApiKey expected = new VirtualApiKey(
				hash, "gw-", "owner-7", "prod-key", 120, 9000,
				Set.of("gpt-4", "gpt-4o"), Set.of("openai", "anthropic"), true, createdAt
		);
		assertEquals(Optional.of(expected), result);
	}

	@Test
	void findByHashTrimsCsvEntriesAndDropsBlanks() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk");
		stubPresent(
				hash, fields(
						"owner", "name", "5", "50", "true",
						" gpt-4 ,,gpt-4o ", " openai , ", CREATED_AT, "gw-"
				)
		);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of("gpt-4", "gpt-4o"), result.get().allowedModels());
		assertEquals(Set.of("openai"), result.get().allowedProviders());
	}

	@Test
	void findByHashDefaultsKeyPrefixWhenAbsent() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-llllllllllllllllllllllllllllllll");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.remove("keyPrefix");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals("gw-", result.get().keyPrefix());
	}

	@Test
	void findByHashTreatsMissingEnabledFlagAsDisabled() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.remove("enabled");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertFalse(result.get().enabled());
	}

	@Test
	void findByHashReturnsEmptyForMalformedOrIncompleteStoredData() {
		KeyManagementService service = newService();

		SHA256Hash missingRpmLimit = hashOf("gw-ffffffffffffffffffffffffffffffff");
		Map<String, String> noRpm = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		noRpm.remove("rpmLimit");
		stubPresent(missingRpmLimit, noRpm);
		assertTrue(service.findByHash(missingRpmLimit).isEmpty());

		SHA256Hash malformedRpmLimit = hashOf("gw-gggggggggggggggggggggggggggggggg");
		stubPresent(
				malformedRpmLimit,
				fields("owner", "name", "not-a-number", "50", "true", "", "", CREATED_AT, "gw-")
		);
		assertTrue(service.findByHash(malformedRpmLimit).isEmpty());

		SHA256Hash malformedCreatedAt = hashOf("gw-hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh");
		stubPresent(
				malformedCreatedAt,
				fields("owner", "name", "5", "50", "true", "", "", "not-a-date", "gw-")
		);
		assertTrue(service.findByHash(malformedCreatedAt).isEmpty());

		SHA256Hash emptyEntries = hashOf("gw-iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii");
		when(redisTemplate.hasKey(redisKey(emptyEntries))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(emptyEntries))).thenReturn(Map.of());
		assertTrue(service.findByHash(emptyEntries).isEmpty());
	}

	@Test
	void findByHashPropagatesRedisFailuresFailClosed() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
		when(redisTemplate.hasKey(redisKey(hash)))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		assertThrows(RedisConnectionFailureException.class, () -> service.findByHash(hash));
		// Failures are never cached: the next lookup attempts Redis again.
		assertThrows(RedisConnectionFailureException.class, () -> service.findByHash(hash));
		verify(redisTemplate, times(2)).hasKey(redisKey(hash));
	}

	@Test
	void seedBootstrapKeysStoresOnlyMissingKeysAndIsIdempotent() {
		KeyManagementService service = newService();
		BootstrapKey fresh = new BootstrapKey(
				"owner-a", "key-a", "gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1, 1, Set.of(), Set.of());
		BootstrapKey existing = new BootstrapKey(
				"owner-b", "key-b", "gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2, 2, Set.of(), Set.of());
		String freshKey = redisKey(hashOf(fresh.plaintextKey()));
		String existingKey = redisKey(hashOf(existing.plaintextKey()));
		// First read of the fresh key is a miss; once seeded the key exists (as in real Redis).
		when(redisTemplate.hasKey(freshKey)).thenReturn(Boolean.FALSE, Boolean.TRUE);
		when(redisTemplate.hasKey(existingKey)).thenReturn(Boolean.TRUE);
		when(hashOps.entries(freshKey))
				.thenReturn(fields("owner-a", "key-a", "1", "1", "true", "", "", CREATED_AT, "gw-"));
		when(hashOps.entries(existingKey))
				.thenReturn(fields("owner-b", "key-b", "2", "2", "true", "", "", CREATED_AT, "gw-"));

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fresh, existing));

		service.seedBootstrapKeys(properties);
		service.seedBootstrapKeys(properties);

		verify(hashOps, times(1)).putAll(anyString(), anyMap());
		verify(hashOps).putAll(eq(freshKey), anyMap());
	}

	@Test
	void seedBootstrapKeysSkipsBlankAndNullPlaintextKeys() {
		KeyManagementService service = new KeyManagementService(redisTemplate);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(
				new BootstrapKey("o1", "n1", null, 1, 1, Set.of(), Set.of()),
				new BootstrapKey("o2", "n2", "", 1, 1, Set.of(), Set.of()),
				new BootstrapKey("o3", "n3", "   ", 1, 1, Set.of(), Set.of())
		));

		service.seedBootstrapKeys(properties);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	void seedBootstrapKeysDoesNotOverwriteExistingKey() {
		KeyManagementService service = newService();
		BootstrapKey existing = new BootstrapKey(
				"owner-b", "key-b", "gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2, 2, Set.of(), Set.of());
		String existingKey = redisKey(hashOf(existing.plaintextKey()));
		stubPresent(
				hashOf(existing.plaintextKey()),
				fields("owner-b", "key-b", "2", "2", "true", "", "", CREATED_AT, "gw-")
		);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(existing));

		service.seedBootstrapKeys(properties);
		service.seedBootstrapKeys(properties);

		verify(hashOps, never()).putAll(anyString(), anyMap());
		verify(redisTemplate, times(1)).hasKey(existingKey);
	}

	@Test
	void seedBootstrapKeysWithEmptyListDoesNotTouchRedis() {
		KeyManagementService service = new KeyManagementService(redisTemplate);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of());

		service.seedBootstrapKeys(properties);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	void seedBootstrapKeysPropagatesRedisFailuresForTheSeederToHandle() {
		KeyManagementService service = newService();
		BootstrapKey key = new BootstrapKey(
				"owner", "name", "gw-cccccccccccccccccccccccccccccccc", 1, 1, Set.of(), Set.of());
		when(redisTemplate.hasKey(redisKey(hashOf(key.plaintextKey()))))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));

		assertThrows(RedisConnectionFailureException.class, () -> service.seedBootstrapKeys(properties));
	}

	@Test
	void seedBootstrapKeyWithoutDashKeepsFullPlaintextAsPrefix() {
		KeyManagementService service = newService();
		String noDash = "noprefixkeyabcdefghijklmnopqrstuvwxyz";
		BootstrapKey key = new BootstrapKey("owner", "name", noDash, 10, 100, Set.of(), Set.of());
		when(redisTemplate.hasKey(redisKey(hashOf(noDash)))).thenReturn(Boolean.FALSE);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hashOf(noDash))), captor.capture());
		assertEquals(noDash, captor.getValue().get("keyPrefix"));
	}

	@Test
	void seedBootstrapKeyWithNullAllowListsStoresEmptyCsv() {
		KeyManagementService service = newService();
		BootstrapKey key = new BootstrapKey("owner", "name", FIXED_PLAINTEXT, 10, 100, null, null);
		when(redisTemplate.hasKey(redisKey(hashOf(FIXED_PLAINTEXT)))).thenReturn(Boolean.FALSE);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hashOf(FIXED_PLAINTEXT))), captor.capture());
		assertEquals("", captor.getValue().get("allowedModels"));
		assertEquals("", captor.getValue().get("allowedProviders"));
	}

	@Test
	void missingAllowListFieldsInRedisParseToEmptySets() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy");
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("ownerId", "owner");
		entries.put("name", "name");
		entries.put("rpmLimit", "10");
		entries.put("tpmLimit", "100");
		entries.put("enabled", "true");
		entries.put("createdAt", CREATED_AT);
		entries.put("keyPrefix", "gw-");
		stubPresent(hash, entries);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of(), result.get().allowedModels());
		assertEquals(Set.of(), result.get().allowedProviders());
	}

	@Test
	void emptyEntriesMapDegradesToEmptyResult() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(Map.of());

		assertTrue(service.findByHash(hash).isEmpty());
	}

	@Test
	void malformedStoredMetadataDegradesToEmptyResult() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
		Map<String, String> bad = fields("owner", "name", "not-a-number", "100", "true", "", "", CREATED_AT, "gw-");
		stubPresent(hash, bad);

		assertTrue(service.findByHash(hash).isEmpty());
	}
}