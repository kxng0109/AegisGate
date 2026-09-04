package io.github.kxng0109.aegisgate.mcp.protocol;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast-path L7 HTTP header parser and validator for the Model Context Protocol (MCP) Streamable HTTP transport.
 */
public final class McpHeaderNormalizer {

	public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
	public static final String HEADER_MCP_METHOD = "Mcp-Method";
	public static final String HEADER_MCP_NAME = "Mcp-Name";
	public static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";
	public static final String HEADER_PARAM_PREFIX = "Mcp-Param-";

	private static final Pattern BASE64_SENTINEL = Pattern.compile("^=\\?base64\\?([A-Za-z0-9+/=]+)\\?=$");
	private static final Pattern VALID_HEADER_NAME = Pattern.compile("^[a-zA-Z0-9!#$%&'*+\\-.^_`|~]+$");

	private McpHeaderNormalizer() {
	}

	/**
	 * Decodes a header value that may be encoded using the MCP Base64 sentinel format: {@code =?base64?<encoded>?=}. If
	 * not encoded, returns the original string trimmed.
	 *
	 * @param rawValue raw HTTP header value
	 * @return decoded UTF-8 string, or empty string if input is null
	 */
	public static String decodeHeaderValue(@Nullable String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return "";
		}
		String trimmed = rawValue.trim();
		Matcher matcher = BASE64_SENTINEL.matcher(trimmed);
		if (matcher.matches()) {
			try {
				byte[] decoded = Base64.getDecoder().decode(matcher.group(1));
				return new String(decoded, StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ignored) {
				return trimmed;
			}
		}
		return trimmed;
	}

	/**
	 * Encodes a non-ASCII or unsafe string into the MCP Base64 sentinel header format.
	 *
	 * @param value plaintext string
	 * @return encoded header string
	 */
	public static String encodeHeaderValue(String value) {
		if (value == null) {
			return "";
		}
		boolean isAscii = true;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 32 || c > 126 || c == '=' || c == '?') {
				isAscii = false;
				break;
			}
		}
		if (isAscii) {
			return value;
		}
		String b64 = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
		return "=?base64?" + b64 + "?=";
	}

	/**
	 * Validates whether a header token complies with RFC 9110 token constraints (no CR/LF, valid tchar).
	 *
	 * @param token header name or token
	 * @return true if valid
	 */
	public static boolean isValidHeaderToken(@Nullable String token) {
		if (token == null || token.isBlank()) {
			return false;
		}
		return VALID_HEADER_NAME.matcher(token).matches();
	}
}
