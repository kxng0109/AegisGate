package io.github.kxng0109.aegisgate.security.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ZeroDataRetentionEnforcer Tests")
class ZeroDataRetentionEnforcerTest {

	private final ZeroDataRetentionEnforcer enforcer = new ZeroDataRetentionEnforcer();

	@Test
	@DisplayName("applyHeaders injects X-No-Storage header")
	void applyHeadersInjectsZdrHeader() {
		HttpHeaders headers = new HttpHeaders();
		enforcer.applyHeaders(headers);

		assertThat(headers.getFirst(ZeroDataRetentionEnforcer.HEADER_NO_STORAGE))
				.isEqualTo(ZeroDataRetentionEnforcer.HEADER_NO_STORAGE_VALUE);

		// Null headers handled safely
		enforcer.applyHeaders(null);
	}

	@Test
	@DisplayName("wipe overwrites memory buffer with zero bytes")
	void wipeSanitizesBuffer() {
		byte[] secretData = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
		ZeroDataRetentionEnforcer.wipe(secretData);

		assertThat(secretData).containsOnly((byte) 0);

		// Null buffer handled safely
		ZeroDataRetentionEnforcer.wipe(null);
	}
}
