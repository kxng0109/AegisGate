package io.github.kxng0109.aegisgate.mcp.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Header Normalizer Unit Tests")
class McpHeaderNormalizerTest {

	@Test
	@DisplayName("decodeHeaderValue decodes valid MIME Base64 sentinels and passes plain ASCII through")
	void decodeHeaderValueScenarios() {
		// Valid Base64 sentinel
		String encoded = "=?base64?cG9zdGdyZXNfX3J1bl9xdWVyeQ==?=";
		assertThat(McpHeaderNormalizer.decodeHeaderValue(encoded)).isEqualTo("postgres__run_query");

		// Plain ASCII string untouched
		assertThat(McpHeaderNormalizer.decodeHeaderValue("postgres__run_query")).isEqualTo("postgres__run_query");

		// Null and blank handling
		assertThat(McpHeaderNormalizer.decodeHeaderValue(null)).isEmpty();
		assertThat(McpHeaderNormalizer.decodeHeaderValue("   ")).isEmpty();

		// Malformed base64 fallback to raw text
		String malformed = "=?base64?***not-valid-b64***?=";
		assertThat(McpHeaderNormalizer.decodeHeaderValue(malformed)).isEqualTo(malformed);
	}

	@Test
	@DisplayName("encodeHeaderValue encodes non-ASCII characters and preserves ASCII strings")
	void encodeHeaderValueScenarios() {
		// Pure ASCII
		assertThat(McpHeaderNormalizer.encodeHeaderValue("simple_tool")).isEqualTo("simple_tool");

		// Null handling
		assertThat(McpHeaderNormalizer.encodeHeaderValue(null)).isEmpty();

		// Non-ASCII string containing unicode characters
		String unicodeText = "tool_with_unicode_✓";
		String encoded = McpHeaderNormalizer.encodeHeaderValue(unicodeText);
		assertThat(encoded).startsWith("=?base64?").endsWith("?=");
		assertThat(McpHeaderNormalizer.decodeHeaderValue(encoded)).isEqualTo(unicodeText);

		// String with forbidden sentinel chars (= or ?)
		String specialChars = "tool=test?param";
		String encodedSpecial = McpHeaderNormalizer.encodeHeaderValue(specialChars);
		assertThat(encodedSpecial).startsWith("=?base64?");
		assertThat(McpHeaderNormalizer.decodeHeaderValue(encodedSpecial)).isEqualTo(specialChars);
	}

	@Test
	@DisplayName("isValidHeaderToken validates RFC 9110 token conformance")
	void isValidHeaderTokenValidation() {
		assertThat(McpHeaderNormalizer.isValidHeaderToken("Mcp-Method")).isTrue();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("tools_list")).isTrue();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("X-Admin-Key-123")).isTrue();

		// Invalid tokens containing whitespace, control characters or CRLF
		assertThat(McpHeaderNormalizer.isValidHeaderToken(null)).isFalse();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("")).isFalse();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("invalid token with spaces")).isFalse();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("header\r\ninjection: evil")).isFalse();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("header\ninjection: evil")).isFalse();
		assertThat(McpHeaderNormalizer.isValidHeaderToken("header:colon")).isFalse();
	}
}
