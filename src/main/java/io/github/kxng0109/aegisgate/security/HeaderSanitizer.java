package io.github.kxng0109.aegisgate.security;

import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Rewrites HTTP headers crossing the gateway trust boundary in both directions.
 * <p>
 * Request direction (client to upstream) uses a deny-list because clients legitimately send
 * many headers: client credentials are stripped so they never reach the provider, hop-by-hop
 * headers are removed as required for proxy forwarding, compression negotiation is dropped so
 * the streamed response arrives uncompressed, and the gateway's own upstream authorization is
 * injected last so nothing the client sent can survive it.
 * <p>
 * Response direction (upstream to client) uses an allow-list because the set of headers a
 * client needs from upstream is tiny and known, while the set of junk or hostile headers an
 * attacker could plant is not enumerable. Server identity headers such as Server,
 * X-Powered-By and Set-Cookie therefore cannot survive.
 * <p>
 * All name comparisons are case-insensitive per HTTP semantics; clients do not reliably
 * canonicalize header casing. Both methods are pure: the input map is never modified, and a
 * fresh ordered map is returned.
 */
@Component
@RequiredArgsConstructor
public class HeaderSanitizer {

	/**
	 * Exact-name deny-list for the request direction. Hop-by-hop names follow RFC 7230
	 * section 6.1; a proxy forwarding them risks protocol confusion on the upstream leg.
	 */
	private static final List<String> STRIPPED_HEADER_NAMES = List.of(
			"Authorization",
			"Host",
			"Cookie",
			"X-Real-IP",
			"X-Api-Key",
			"Accept-Encoding",
			"Content-Length",
			"Connection",
			"Keep-Alive",
			"Proxy-Authenticate",
			"Proxy-Authorization",
			"TE",
			"Trailer",
			"Transfer-Encoding",
			"Upgrade"
	);

	/**
	 * Prefix deny-list for families of internal or spoofable headers; matched with
	 * starts-with semantics rather than substring semantics so unrelated names survive.
	 */
	private static final List<String> STRIPPED_HEADER_PREFIXES = List.of(
			"X-Forwarded-",
			"X-Internal-",
			"X-Gateway-"
	);

	/**
	 * The complete set of upstream response headers a Phase 1 SSE client needs.
	 */
	private static final Set<String> RESPONSE_ALLOWED_NAMES = Set.of("Content-Type");

	private final UpstreamConfig upstreamConfig;

	/**
	 * Builds the header map for the outgoing upstream request from the client's headers.
	 * <p>
	 * Strips denied names and prefix families, injects {@code Authorization: Bearer} using the
	 * configured upstream key, and forces {@code Content-Type: application/json} regardless of
	 * what the client claimed. The injected values are written after stripping, so a client
	 * header cannot override them by any casing trick.
	 *
	 * @param clientHeaders headers received from the downstream client
	 * @return a fresh ordered map safe to feed to the HTTP request builder
	 */
	public Map<String, String> sanitizeRequestHeaders(Map<String, String> clientHeaders) {
		Objects.requireNonNull(clientHeaders, "clientHeaders must not be null");

		Map<String, String> sanitized = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
			if (!isStripped(entry.getKey())) {
				sanitized.put(entry.getKey(), entry.getValue());
			}
		}

		sanitized.put("Authorization", "Bearer " + upstreamConfig.apiKey().value());
		sanitized.put("Content-Type", "application/json");
		return sanitized;
	}

	/**
	 * Builds the downstream response header map from the upstream response headers.
	 * <p>
	 * Copies only allow-listed upstream names, then adds the streaming headers required by the
	 * SSE contract. The Connection header is deliberately not set: the servlet container owns
	 * connection management on the downstream leg.
	 *
	 * @param upstreamHeaders headers received from the upstream provider
	 * @return a fresh ordered map to apply to the downstream response
	 */
	public Map<String, String> sanitizeResponseHeaders(Map<String, String> upstreamHeaders) {
		Objects.requireNonNull(upstreamHeaders, "upstreamHeaders must not be null");

		Map<String, String> downstream = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : upstreamHeaders.entrySet()) {
			if (RESPONSE_ALLOWED_NAMES.stream().anyMatch(entry.getKey()::equalsIgnoreCase)) {
				downstream.put(entry.getKey(), entry.getValue());
			}
		}

		downstream.put("Cache-Control", "no-cache");
		downstream.put("X-Accel-Buffering", "no");
		return downstream;
	}

	private boolean isStripped(String headerName) {
		for (String stripped : STRIPPED_HEADER_NAMES) {
			if (headerName.equalsIgnoreCase(stripped)) {
				return true;
			}
		}
		for (String prefix : STRIPPED_HEADER_PREFIXES) {
			if (headerName.length() >= prefix.length()
					&& headerName.regionMatches(true, 0, prefix, 0, prefix.length())) {
				return true;
			}
		}
		return false;
	}
}
