package io.github.kxng0109.aegisgate.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for gateway-terminated idempotency keys.
 */
@DisplayName("IdempotencyKeys")
class IdempotencyKeysTest {

	@Test
	@DisplayName("absent or blank header means no idempotency (null)")
	void absentOrBlankMeansNull() {
		assertNull(IdempotencyKeys.validateOrNull(null));
		assertNull(IdempotencyKeys.validateOrNull(""));
		assertNull(IdempotencyKeys.validateOrNull("   "));
	}

	@Test
	@DisplayName("oversize and non-printable keys are rejected")
	void malformedKeysRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> IdempotencyKeys.validateOrNull("k".repeat(256)));
		assertThrows(IllegalArgumentException.class,
				() -> IdempotencyKeys.validateOrNull("key with spaces"));
		assertThrows(IllegalArgumentException.class,
				() -> IdempotencyKeys.validateOrNull("key\nnewline"));
		assertThrows(IllegalArgumentException.class,
				() -> IdempotencyKeys.validateOrNull("clé"));
	}

	@Test
	@DisplayName("derivation is deterministic per logical operation")
	void derivationIsDeterministic() {
		UUID first = IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/chat/completions", "abc123");
		UUID second = IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/chat/completions", "abc123");

		assertEquals(first, second);
	}

	@Test
	@DisplayName("any input change yields a distinct id")
	void distinctInputsYieldDistinctIds() {
		UUID base = IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/chat/completions", "abc123");

		assertNotEquals(base, IdempotencyKeys.resolveRequestId("op-2", "tenant-a", "/v1/chat/completions", "abc123"));
		assertNotEquals(base, IdempotencyKeys.resolveRequestId("op-1", "tenant-b", "/v1/chat/completions", "abc123"));
		assertNotEquals(base, IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/embeddings", "abc123"));
		assertNotEquals(base, IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/chat/completions", "def456"));
	}

	@Test
	@DisplayName("derived ids are RFC 4122 version 5 with variant 2")
	void derivedIdsCarryVersionAndVariantBits() {
		UUID id = IdempotencyKeys.resolveRequestId("op-1", "tenant-a", "/v1/chat/completions", "abc123");

		assertEquals(5, id.version());
		assertEquals(2, id.variant());
	}

	@Test
	@DisplayName("absent key preserves random-id behavior")
	void absentKeyStaysRandom() {
		UUID first = IdempotencyKeys.resolveRequestId(null, "tenant-a", "/v1/chat/completions", "abc123");
		UUID second = IdempotencyKeys.resolveRequestId(null, "tenant-a", "/v1/chat/completions", "abc123");

		assertNotEquals(first, second);
	}

	@Test
	@DisplayName("sha256Hex is stable lowercase hex")
	void sha256HexIsStable() {
		String first = IdempotencyKeys.sha256Hex(new byte[]{1, 2, 3});
		String second = IdempotencyKeys.sha256Hex(new byte[]{1, 2, 3});

		assertEquals(first, second);
		assertTrue(first.matches("[0-9a-f]{64}"));
	}
}
