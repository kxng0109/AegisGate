package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SuppressWarnings("DataFlowIssue")
class SHA256HashTest {

	@Test
	void fromRawKeyIsDeterministic() {
		SHA256Hash a = SHA256Hash.fromRawKey("gw-secret-1");
		SHA256Hash b = SHA256Hash.fromRawKey("gw-secret-1");
		assertEquals(a, b);
		assertEquals(a.hex(), b.hex());
	}

	@Test
	void differentKeysProduceDifferentHashes() {
		assertNotEquals(SHA256Hash.fromRawKey("gw-a"), SHA256Hash.fromRawKey("gw-b"));
	}

	@Test
	void fromHexRoundTrips() {
		SHA256Hash original = SHA256Hash.fromRawKey("gw-x");
		assertEquals(original, SHA256Hash.fromHex(original.hex()));
	}

	@Test
	void toStringIsMasked() {
		assertEquals("****", SHA256Hash.fromRawKey("gw-x").toString());
	}

	@Test
	void hexIsSixtyFourCharacters() {
		assertEquals(64, SHA256Hash.fromRawKey("gw-x").hex().length());
	}

	@Test
	void equalsRejectsNullAndForeignTypes() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-x");
		assertNotEquals(null, hash);
		assertNotEquals("not-a-hash", hash);
	}

	@Test
	void equalsInvokedDirectlyRejectsNullAndForeignTypes() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-x");
		assertNotEquals(null, hash);
		assertNotEquals("not-a-hash", hash);
	}
}
