package io.github.kxng0109.aegisgate.security;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderSanitizerTest {

	private final HeaderSanitizer sanitizer =
			new HeaderSanitizer(new UpstreamConfig(
					"openai-primary", "https://api.openai.com/v1", new SensitiveString("sk-live-secret")));

	@Test
	@DisplayName("strips exact-name deny-list entries regardless of client casing")
	void stripsExactNamesRegardlessOfCasing() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(Map.of(
				"authorization", "Bearer client-token",
				"HOST", "client-host",
				"cOoKiE", "session=abc"));

		assertFalse(sanitized.containsKey("authorization"));
		assertFalse(sanitized.containsKey("HOST"));
		assertFalse(sanitized.containsKey("cOoKiE"));
	}

	@Test
	@DisplayName("strips every hop-by-hop header required by RFC 7230")
	void stripsHopByHopHeaders() {
		Map<String, String> client = new HashMap<>();
		client.put("Connection", "keep-alive");
		client.put("Keep-Alive", "timeout=5");
		client.put("Proxy-Authenticate", "Basic realm=x");
		client.put("Proxy-Authorization", "Basic dXNlcjpwYXNz");
		client.put("TE", "trailers");
		client.put("Trailer", "Expires");
		client.put("Transfer-Encoding", "chunked");
		client.put("Upgrade", "websocket");

		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(client);

		for (String stripped : client.keySet()) {
			assertFalse(sanitized.containsKey(stripped), stripped + " must not survive");
		}
	}

	@Test
	@DisplayName("strips compression negotiation and stale content length")
	void stripsCompressionAndContentLength() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(Map.of(
				"Accept-Encoding", "gzip, br",
				"Content-Length", "1234"));

		assertFalse(sanitized.containsKey("Accept-Encoding"));
		assertFalse(sanitized.containsKey("Content-Length"));
	}

	@Test
	@DisplayName("strips prefix families using starts-with semantics")
	void stripsPrefixFamiliesWithStartsWithSemantics() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(Map.of(
				"X-Forwarded-For", "203.0.113.9",
				"x-forwarded-proto", "https",
				"X-Gateway-Trace", "abc",
				"X-Internal-Route", "secret-route",
				"X-Real-IP", "10.0.0.1",
				"X-Api-Key", "client-api-key"));

		assertFalse(sanitized.containsKey("X-Forwarded-For"));
		assertFalse(sanitized.containsKey("x-forwarded-proto"));
		assertFalse(sanitized.containsKey("X-Gateway-Trace"));
		assertFalse(sanitized.containsKey("X-Internal-Route"));
		assertFalse(sanitized.containsKey("X-Real-IP"));
		assertFalse(sanitized.containsKey("X-Api-Key"));
	}

	@Test
	@DisplayName("keeps innocent end-to-end headers that contain denied words as substrings")
	void keepsInnocentHeadersContainingDeniedSubstrings() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(Map.of(
				"X-Database-Host", "db.internal",
				"User-Agent", "aegisgate-integration-test"));

		assertEquals("db.internal", sanitized.get("X-Database-Host"));
		assertEquals("aegisgate-integration-test", sanitized.get("User-Agent"));
	}

	@Test
	@DisplayName("injects the real upstream key as a Bearer token")
	void injectsRealUpstreamKeyAsBearerToken() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(Map.of());

		assertEquals("Bearer sk-live-secret", sanitized.get("Authorization"));
		assertFalse(sanitized.get("Authorization").contains("****"),
				"masked value must never be sent upstream");
	}

	@Test
	@DisplayName("forces application/json even when the client claims another type")
	void forcesApplicationJsonContentType() {
		Map<String, String> sanitized = sanitizer.sanitizeRequestHeaders(
				Map.of("Content-Type", "text/plain"));

		assertEquals("application/json", sanitized.get("Content-Type"));
	}

	@Test
	@DisplayName("never mutates the caller's header map")
	void neverMutatesCallerMap() {
		Map<String, String> client = new HashMap<>();
		client.put("Authorization", "Bearer client-token");
		client.put("User-Agent", "test-agent");

		sanitizer.sanitizeRequestHeaders(client);

		assertEquals(2, client.size());
		assertEquals("Bearer client-token", client.get("Authorization"));
	}

	@Test
	@DisplayName("request sanitization rejects a null map")
	void rejectsNullRequestHeaderMap() {
		assertThrows(NullPointerException.class, () -> sanitizer.sanitizeRequestHeaders(null));
	}

	@Test
	@DisplayName("response direction copies only the allow-listed upstream headers")
	void responseDirectionCopiesOnlyAllowedUpstreamHeaders() {
		Map<String, String> upstream = new HashMap<>();
		upstream.put("Content-Type", "text/event-stream; charset=utf-8");
		upstream.put("Set-Cookie", "track=1; Secure");
		upstream.put("Server", "cloudflare");
		upstream.put("X-Powered-By", "sensitive-stack/1.0");
		upstream.put("Content-Security-Policy", "default-src 'none'");

		Map<String, String> downstream = sanitizer.sanitizeResponseHeaders(upstream);

		assertEquals("text/event-stream; charset=utf-8", downstream.get("Content-Type"));
		assertNull(downstream.get("Set-Cookie"));
		assertNull(downstream.get("Server"));
		assertNull(downstream.get("X-Powered-By"));
		assertNull(downstream.get("Content-Security-Policy"));
	}

	@Test
	@DisplayName("response direction adds the SSE streaming contract headers")
	void responseDirectionAddsStreamingContractHeaders() {
		Map<String, String> downstream = sanitizer.sanitizeResponseHeaders(
				Map.of("Content-Type", "text/event-stream"));

		assertEquals("no-cache", downstream.get("Cache-Control"));
		assertEquals("no", downstream.get("X-Accel-Buffering"));
		assertFalse(downstream.containsKey("Connection"),
				"connection management belongs to the servlet container");
	}

	@Test
	@DisplayName("response direction tolerates upstream responses without allowed headers")
	void responseDirectionToleratesMissingAllowedHeaders() {
		Map<String, String> downstream = assertDoesNotThrow(() ->
				sanitizer.sanitizeResponseHeaders(Map.of("Server", "opaque")));

		assertNotNull(downstream);
		assertFalse(downstream.containsKey("Content-Type"));
		assertTrue(downstream.containsKey("Cache-Control"));
	}

	@Test
	@DisplayName("response sanitization rejects a null map")
	void rejectsNullResponseHeaderMap() {
		assertThrows(NullPointerException.class, () -> sanitizer.sanitizeResponseHeaders(null));
	}
}
