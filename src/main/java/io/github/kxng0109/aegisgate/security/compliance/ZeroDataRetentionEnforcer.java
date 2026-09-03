package io.github.kxng0109.aegisgate.security.compliance;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Enforces Zero Data Retention (ZDR) across upstream providers and local memory buffers.
 */
@Component
public class ZeroDataRetentionEnforcer {

	public static final String HEADER_NO_STORAGE = "X-No-Storage";
	public static final String HEADER_NO_STORAGE_VALUE = "1";

	/**
	 * Injects zero-data-retention headers into upstream HTTP request headers.
	 *
	 * @param headers target HTTP headers
	 */
	public void applyHeaders(HttpHeaders headers) {
		if (headers != null) {
			headers.set(HEADER_NO_STORAGE, HEADER_NO_STORAGE_VALUE);
		}
	}

	/**
	 * Sanitizes a sensitive in-memory byte buffer by overwriting it with zero bytes.
	 *
	 * @param buffer memory buffer to wipe
	 */
	public static void wipe(byte[] buffer) {
		if (buffer != null) {
			Arrays.fill(buffer, (byte) 0);
		}
	}
}
