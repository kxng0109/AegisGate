package io.github.kxng0109.aegisgate.security.filter;

import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitEngine;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Authenticates a virtual API key ({@code Authorization: Bearer gw-...}) and enforces the distributed rate limit before
 * the request reaches the proxy controller.
 *
 * <p>Pipeline (in order):</p>
 * <ol>
 *   <li><b>Format gate</b>  -  the token must be {@code gw-} + exactly
 *       32 URL-safe characters; junk is rejected before hashing so the
 *       negative cache is not polluted.</li>
 *   <li><b>Key lookup (fail closed)</b>  -  a Redis outage surfaces 503, never a
 *      4xx that could be misread as "key absent".</li>
 *   <li><b>Model allow-list</b>  -  enforced before the rate-limit check.</li>
 *   <li><b>Rate limit</b>  -  RPM + TPM via the Lua engine; rejection yields 429
 *       with {@code Retry-After} and the {@code X-RateLimit-*} family.</li>
 * </ol>
 *
 * <p>On success it sets the {@code X-RateLimit-*} response headers and the
 * {@code aegis.ownerId} request attribute for downstream attribution, then
 * continues the chain. The request body is read exclusively from the
 * {@link CachedBodyHttpServletRequest} installed by
 * {@link RequestBodyCachingFilter}; the raw stream is never consumed here.</p>
 */
public class KeyAuthFilter extends OncePerRequestFilter {

	/**
	 * Registration order: runs after the body-caching filter.
	 */
	public static final int ORDER = 1;

	/**
	 * The only path this filter applies to.
	 */
	public static final String TARGET_PATH = "/v1/chat/completions";

	/**
	 * The {@code Bearer } scheme prefix.
	 */
	public static final String AUTH_SCHEME = "Bearer ";

	/**
	 * Key prefix that identifies a gateway virtual API key.
	 */
	public static final String KEY_PREFIX = "gw-";

	/**
	 * Number of random URL-safe characters after the prefix.
	 */
	public static final int KEY_SUFFIX_LENGTH = 32;

	/**
	 * Compiled pattern for the random suffix (base64url alphabet).
	 */
	public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32}");

	/**
	 * Token estimate used when the body carries no usable token limit.
	 */
	public static final int DEFAULT_ESTIMATED_TOKENS = 100;

	/**
	 * Upper clamp for the pre-flight token estimate.
	 */
	public static final int MAX_ESTIMATED_TOKENS = 1_000_000;

	/**
	 * Request attribute that carries the resolved owner id downstream.
	 */
	public static final String OWNER_ID_ATTRIBUTE = "aegis.ownerId";

	/**
	 * RPM limit header.
	 */
	public static final String HEADER_LIMIT_RPM = "X-RateLimit-Limit-RPM";

	/**
	 * RPM remaining header.
	 */
	public static final String HEADER_REMAINING_RPM = "X-RateLimit-Remaining-RPM";

	/**
	 * RPM reset header (epoch seconds).
	 */
	public static final String HEADER_RESET_RPM = "X-RateLimit-Reset-RPM";

	/**
	 * TPM limit header.
	 */
	public static final String HEADER_LIMIT_TPM = "X-RateLimit-Limit-TPM";

	/**
	 * TPM remaining header.
	 */
	public static final String HEADER_REMAINING_TPM = "X-RateLimit-Remaining-TPM";

	/**
	 * TPM reset header (epoch seconds).
	 */
	public static final String HEADER_RESET_TPM = "X-RateLimit-Reset-TPM";

	/**
	 * Header value used when a dimension is unlimited.
	 */
	public static final String UNLIMITED_HEADER_VALUE = "unlimited";

	private final KeyManagementService keyManagementService;
	private final RateLimitEngine rateLimitEngine;
	private final ObjectMapper objectMapper;

	/**
	 * @param keyManagementService key lookup service
	 * @param rateLimitEngine      distributed rate-limit engine
	 * @param objectMapper         Jackson (tools.jackson) mapper for JSON bodies/errors
	 */
	public KeyAuthFilter(
			KeyManagementService keyManagementService,
			RateLimitEngine rateLimitEngine,
			ObjectMapper objectMapper
	) {
		this.keyManagementService = keyManagementService;
		this.rateLimitEngine = rateLimitEngine;
		this.objectMapper = objectMapper;
	}

	/**
	 * Validates the token shape: {@code gw-} prefix plus exactly 32 base64url characters. Cheap rejection keeps the
	 * negative cache clean.
	 *
	 * @param rawKey the token after the {@code Bearer } scheme
	 * @return {@code true} if the key is well formed
	 */
	static boolean isWellFormedKey(String rawKey) {
		if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) {
			return false;
		}
		String suffix = rawKey.substring(KEY_PREFIX.length());
		return suffix.length() == KEY_SUFFIX_LENGTH && KEY_PATTERN.matcher(suffix).matches();
	}

	/**
	 * Sets a limit header, using {@link #UNLIMITED_HEADER_VALUE} for unlimited dimensions (limit {@code 0}).
	 *
	 * @param response the servlet response
	 * @param name     header name
	 * @param limit    configured limit
	 */
	private static void setLimitHeader(HttpServletResponse response, String name, int limit) {
		response.setHeader(name, limit == 0 ? UNLIMITED_HEADER_VALUE : Integer.toString(limit));
	}

	/**
	 * @param reason rejection reason
	 * @return the client-facing message for the reason
	 */
	static String rejectionMessage(RejectionReason reason) {
		return switch (reason) {
			case RPM_EXCEEDED -> "Request rate limit exceeded. Retry after the indicated period.";
			case TPM_EXCEEDED -> "Token rate limit exceeded. Retry after the indicated period.";
			case KEY_DISABLED -> "API key is disabled.";
			case KEY_NOT_FOUND -> "API key not found.";
			case MODEL_NOT_ALLOWED -> "Model not allowed for this key.";
		};
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !HttpMethod.POST.matches(request.getMethod())
				|| !TARGET_PATH.equals(request.getServletPath());
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {

		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authHeader == null
				|| !authHeader.startsWith(AUTH_SCHEME)
				|| authHeader.length() <= AUTH_SCHEME.length()) {
			writeJsonError(
					response, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header",
					RejectionReason.KEY_NOT_FOUND
			);
			return;
		}

		String rawKey = authHeader.substring(AUTH_SCHEME.length()).trim();
		if (!isWellFormedKey(rawKey)) {
			writeJsonError(response, HttpStatus.UNAUTHORIZED, "Invalid API key", RejectionReason.KEY_NOT_FOUND);
			return;
		}

		SHA256Hash keyHash = SHA256Hash.fromRawKey(rawKey);

		Optional<VirtualApiKey> keyOpt;
		try {
			keyOpt = keyManagementService.findByHash(keyHash);
		} catch (DataAccessException | PoolException ex) {
			writeJsonError(response, HttpStatus.SERVICE_UNAVAILABLE, "Authentication service unavailable", null);
			return;
		}
		if (keyOpt.isEmpty()) {
			writeJsonError(response, HttpStatus.UNAUTHORIZED, "Invalid API key", RejectionReason.KEY_NOT_FOUND);
			return;
		}
		VirtualApiKey key = keyOpt.get();
		if (!key.enabled()) {
			writeJsonError(response, HttpStatus.FORBIDDEN, "API key is disabled", RejectionReason.KEY_DISABLED);
			return;
		}

		byte[] bodyBytes = readBodyContent(request);
		String model = extractModel(bodyBytes);
		if (!key.allowedModels().isEmpty()
				&& (model == null || !key.allowedModels().contains(model))) {
			writeJsonError(
					response, HttpStatus.FORBIDDEN, "Model not allowed for this key",
					RejectionReason.MODEL_NOT_ALLOWED
			);
			return;
		}
		int estimatedTokens = extractEstimatedTokens(bodyBytes);

		RateLimitDecision decision;
		try {
			decision = rateLimitEngine.checkRateLimit(keyHash, key, estimatedTokens);
		} catch (RateLimitUnavailableException ex) {
			writeJsonError(response, HttpStatus.SERVICE_UNAVAILABLE, "Rate-limit service unavailable", null);
			return;
		}

		if (decision instanceof RateLimitDecision.Rejected(RejectionReason reason, long afterSeconds)) {
			long nowEpochSecond = System.currentTimeMillis() / 1000L;
			long retryAfterSeconds = Math.max(1, afterSeconds);
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
			setLimitHeader(response, HEADER_LIMIT_RPM, key.rpmLimit());
			response.setHeader(HEADER_REMAINING_RPM, "0");
			response.setHeader(HEADER_RESET_RPM, Long.toString(nowEpochSecond + retryAfterSeconds));
			setLimitHeader(response, HEADER_LIMIT_TPM, key.tpmLimit());
			response.setHeader(HEADER_REMAINING_TPM, "0");
			response.setHeader(HEADER_RESET_TPM, Long.toString(nowEpochSecond + retryAfterSeconds));
			writeJsonError(
					response, HttpStatus.TOO_MANY_REQUESTS,
					rejectionMessage(reason), reason
			);
			return;
		}

		RateLimitState state = ((RateLimitDecision.Allowed) decision).state();
		setLimitHeader(response, HEADER_LIMIT_RPM, state.rpmLimit());
		response.setHeader(HEADER_REMAINING_RPM, Integer.toString(state.rpmRemaining()));
		response.setHeader(HEADER_RESET_RPM, Long.toString(state.rpmResetAt().getEpochSecond()));
		setLimitHeader(response, HEADER_LIMIT_TPM, state.tpmLimit());
		response.setHeader(HEADER_REMAINING_TPM, Integer.toString(state.tpmRemaining()));
		response.setHeader(HEADER_RESET_TPM, Long.toString(state.tpmResetAt().getEpochSecond()));

		request.setAttribute(OWNER_ID_ATTRIBUTE, key.ownerId());
		filterChain.doFilter(request, response);
	}

	/**
	 * Reads the buffered body without ever touching the raw request stream.
	 *
	 * @param request the current request
	 * @return the buffered bytes, or an empty array when no wrapper is present
	 */
	private byte[] readBodyContent(HttpServletRequest request) {
		CachedBodyHttpServletRequest wrapper =
				WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
		if (wrapper == null) {
			return new byte[0];
		}
		return wrapper.getContentAsByteArray();
	}

	/**
	 * Extracts the {@code model} field from the buffered body. Returns {@code null} on any parse failure or when the
	 * field is absent.
	 *
	 * @param bodyBytes the buffered request body
	 * @return the model id, or {@code null}
	 */
	private String extractModel(byte[] bodyBytes) {
		if (bodyBytes.length == 0) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(bodyBytes);
			if (root == null || !root.isObject()) {
				return null;
			}
			JsonNode modelNode = root.get("model");
			return modelNode != null && modelNode.isTextual() ? modelNode.asText() : null;
		} catch (JacksonException ignored) {
			return null;
		}
	}

	/**
	 * Extracts the pre-flight token estimate from {@code max_tokens} or {@code max_completion_tokens} (first present
	 * wins), clamped into {@code [1, MAX_ESTIMATED_TOKENS]}.
	 *
	 * @param bodyBytes the buffered request body
	 * @return the estimate, defaulting to {@link #DEFAULT_ESTIMATED_TOKENS}
	 */
	private int extractEstimatedTokens(byte[] bodyBytes) {
		if (bodyBytes.length == 0) {
			return DEFAULT_ESTIMATED_TOKENS;
		}
		try {
			JsonNode root = objectMapper.readTree(bodyBytes);
			if (root == null || !root.isObject()) {
				return DEFAULT_ESTIMATED_TOKENS;
			}
			JsonNode tokenNode = root.get("max_tokens");
			if (tokenNode == null || tokenNode.isNull()) {
				tokenNode = root.get("max_completion_tokens");
			}
			if (tokenNode == null || tokenNode.isNull() || !tokenNode.isNumber()) {
				return DEFAULT_ESTIMATED_TOKENS;
			}
			long value = tokenNode.asLong();
			if (value <= 0) {
				return DEFAULT_ESTIMATED_TOKENS;
			}
			return (int) Math.min(MAX_ESTIMATED_TOKENS, value);
		} catch (JacksonException ignored) {
			return DEFAULT_ESTIMATED_TOKENS;
		}
	}

	/**
	 * Writes a JSON error response.
	 *
	 * @param response the servlet response
	 * @param status   HTTP status
	 * @param message  generic client-facing message
	 * @param reason   rejection reason (its {@code name()} becomes the JSON {@code code})
	 * @throws IOException if the body cannot be written
	 */
	private void writeJsonError(
			HttpServletResponse response,
			HttpStatus status,
			String message,
			RejectionReason reason
	) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		Map<String, Object> error = new LinkedHashMap<>();
		error.put("message", message);
		if (reason != null) {
			error.put("code", reason.name());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}