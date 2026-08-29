package io.github.kxng0109.aegisgate.security.filter;

import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitEngine;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KeyAuthFilter}: authentication format gates, key states, fail-closed Redis outages, model
 * allow-list, token estimation, and rate-limit decision header mapping.
 */
@DisplayName("KeyAuthFilter")
class KeyAuthFilterTest {

	private static final String PATH = "/v1/chat/completions";
	private static final String VALID_SUFFIX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String VALID_KEY = KeyAuthFilter.KEY_PREFIX + VALID_SUFFIX;
	private static final String OWNER = "owner-1";

	private final KeyManagementService kms = mock(KeyManagementService.class);
	private final RateLimitEngine engine = mock(RateLimitEngine.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private KeyAuthFilter filter;

	@BeforeEach
	void setUp() {
		filter = new KeyAuthFilter(kms, engine, objectMapper);
	}

	// ---------------------------------------------------------------------
	// Authenticated-key resolution fixtures
	// ---------------------------------------------------------------------

	private void stubKeyPresent() {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of(), true)));
	}

	private void stubAllowed(int rpmLimit, int rpmRemaining, int tpmLimit, int tpmRemaining) {
		when(engine.checkRateLimit(any(), any(), anyInt())).thenReturn(
				new RateLimitDecision.Allowed(new RateLimitState(
						rpmLimit, rpmRemaining, Instant.ofEpochSecond(1_700_000_000),
						tpmLimit, tpmRemaining, Instant.ofEpochSecond(1_700_000_060)
				)));
	}

	private static VirtualApiKey key(int rpm, int tpm, Set<String> models, boolean enabled) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey(VALID_KEY),
				KeyAuthFilter.KEY_PREFIX,
				OWNER,
				"test",
				rpm,
				tpm,
				models,
				Set.of(),
				enabled,
				Instant.parse("2026-08-28T00:00:00Z")
		);
	}

	private static CachedBodyHttpServletRequest request(String authHeader, String jsonBody) throws IOException {
		MockHttpServletRequest mock = new MockHttpServletRequest("POST", PATH);
		mock.setServletPath(PATH);
		if (authHeader != null) {
			mock.addHeader("Authorization", authHeader);
		}
		if (jsonBody != null) {
			mock.setContent(jsonBody.getBytes(StandardCharsets.UTF_8));
		} else {
			mock.setContent(new byte[0]);
		}
		return new CachedBodyHttpServletRequest(mock);
	}

	private static MockHttpServletResponse invoke(CachedBodyHttpServletRequest request, KeyAuthFilter f)
			throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		f.doFilterInternal(request, response, new MockFilterChain());
		return response;
	}

	private static JsonNode parse(MockHttpServletResponse response) {
		try {
			return new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to parse error response JSON", e);
		}
	}

	// ---------------------------------------------------------------------
	// Authentication: header and format gates
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("missing Authorization header -> 401, chain not invoked")
	void missingHeader() throws Exception {
		CachedBodyHttpServletRequest request = request(null, null);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request, response, chain.chain());

		assertEquals(401, response.getStatus());
		assertTrue(response.getContentType().contains("application/json"));
		JsonNode body = parse(response);
		assertEquals("Missing or malformed Authorization header", body.get("error").get("message").asString());
		assertEquals(RejectionReason.KEY_NOT_FOUND.name(), body.get("error").get("code").asString());
		assertNull(chain.chain().getRequest(), "chain must not run for rejected requests");
	}

	@Test
	@DisplayName("non-Bearer scheme -> 401")
	void nonBearerScheme() throws Exception {
		MockHttpServletResponse response = invoke(request("Basic dXNlcjpwYXNz", null), filter);
		assertEquals(401, response.getStatus());
	}

	@Test
	@DisplayName("empty Bearer token -> 401")
	void emptyBearerToken() throws Exception {
		MockHttpServletResponse response = invoke(request("Bearer ", null), filter);
		assertEquals(401, response.getStatus());
	}

	@Test
	@DisplayName("token without gw- prefix -> 401")
	void missingGwPrefix() throws Exception {
		MockHttpServletResponse response = invoke(request("Bearer not-a-gateway-key", null), filter);
		assertEquals(401, response.getStatus());
		verify(kms, never()).findByHash(any());
	}

	@Test
	@DisplayName("key suffix of wrong length (31) -> 401")
	void suffixTooShort() throws Exception {
		MockHttpServletResponse response = invoke(request("Bearer gw-" + "a".repeat(31), null), filter);
		assertEquals(401, response.getStatus());
	}

	@Test
	@DisplayName("key suffix of wrong length (33) -> 401")
	void suffixTooLong() throws Exception {
		MockHttpServletResponse response = invoke(request("Bearer gw-" + "a".repeat(33), null), filter);
		assertEquals(401, response.getStatus());
	}

	@Test
	@DisplayName("key suffix with invalid alphabet character -> 401")
	void invalidAlphabetCharacter() throws Exception {
		MockHttpServletResponse response = invoke(
				request("Bearer gw-" + "a".repeat(31) + ".", null), filter);
		assertEquals(401, response.getStatus());
		verify(kms, never()).findByHash(any());
	}

	@Test
	@DisplayName("key suffix containing a space -> 401")
	void suffixWithSpace() throws Exception {
		MockHttpServletResponse response = invoke(
				request("Bearer gw-" + "a".repeat(31) + " ", null), filter);
		assertEquals(401, response.getStatus());
	}

	// ---------------------------------------------------------------------
	// Key resolution states
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("hash not found -> 401, chain not invoked")
	void keyNotFound() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.empty());
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request("Bearer " + VALID_KEY, null), response, chain.chain());

		assertEquals(401, response.getStatus());
		JsonNode body = parse(response);
		assertEquals("Invalid API key", body.get("error").get("message").asString());
		assertNull(chain.chain().getRequest());
	}

	@Test
	@DisplayName("disabled key -> 403 KEY_DISABLED")
	void disabledKey() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of(), false)));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(403, response.getStatus());
		JsonNode body = parse(response);
		assertEquals(RejectionReason.KEY_DISABLED.name(), body.get("error").get("code").asString());
	}

	// ---------------------------------------------------------------------
	// Fail-closed: Redis outage
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("DataAccessException from key lookup -> 503")
	void keyLookupDataAccessFailure() throws Exception {
		when(kms.findByHash(any())).thenThrow(new DataAccessResourceFailureException("redis down"));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(503, response.getStatus());
		assertTrue(response.getContentType().contains("application/json"));
	}

	@Test
	@DisplayName("PoolException from key lookup -> 503")
	void keyLookupPoolFailure() throws Exception {
		when(kms.findByHash(any())).thenThrow(new PoolException("pool exhausted"));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(503, response.getStatus());
	}

	@Test
	@DisplayName("RedisConnectionFailureException (a DataAccessException) -> 503")
	void keyLookupRedisConnectionFailure() throws Exception {
		when(kms.findByHash(any())).thenThrow(new RedisConnectionFailureException("unable to connect"));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(503, response.getStatus());
	}

	@Test
	@DisplayName("RateLimitUnavailableException from engine -> 503")
	void engineUnavailable() throws Exception {
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), anyInt()))
				.thenThrow(new RateLimitUnavailableException("backend down"));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(503, response.getStatus());
	}

	// ---------------------------------------------------------------------
	// Model allow-list
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("empty allow-list: any model proceeds")
	void allowlistEmptyProceeds() throws Exception {
		stubKeyPresent();
		stubAllowed(10, 9, 1000, 900);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request("Bearer " + VALID_KEY, "{\"model\":\"gpt-x\"}"), response, chain.chain());
		assertEquals(200, response.getStatus());
		assertNotNull(chain.chain().getRequest());
	}

	@Test
	@DisplayName("model not in allow-list -> 403 MODEL_NOT_ALLOWED")
	void modelNotAllowed() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of("gpt-a"), true)));
		MockHttpServletResponse response = invoke(
				request("Bearer " + VALID_KEY, "{\"model\":\"gpt-b\"}"), filter);
		assertEquals(403, response.getStatus());
		assertEquals(
				RejectionReason.MODEL_NOT_ALLOWED.name(),
				parse(response).get("error").get("code").asString()
		);
	}

	@Test
	@DisplayName("allow-list non-empty and model missing -> 403")
	void modelMissingWithAllowlist() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of("gpt-a"), true)));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, "{}"), filter);
		assertEquals(403, response.getStatus());
	}

	@Test
	@DisplayName("model in allow-list -> proceeds")
	void modelInAllowlist() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of("gpt-a"), true)));
		stubAllowed(10, 9, 1000, 900);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(
				request("Bearer " + VALID_KEY, "{\"model\":\"gpt-a\"}"), response, chain.chain());
		assertEquals(200, response.getStatus());
	}

	@Test
	@DisplayName("malformed JSON with non-empty allow-list -> 403 (model null)")
	void malformedJsonWithAllowlist() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of("gpt-a"), true)));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, "{not json"), filter);
		assertEquals(403, response.getStatus());
		assertEquals(
				RejectionReason.MODEL_NOT_ALLOWED.name(),
				parse(response).get("error").get("code").asString()
		);
	}

	@Test
	@DisplayName("malformed JSON with empty allow-list -> proceeds")
	void malformedJsonWithoutAllowlist() throws Exception {
		stubKeyPresent();
		stubAllowed(10, 9, 1000, 900);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request("Bearer " + VALID_KEY, "{not json"), response, chain.chain());
		assertEquals(200, response.getStatus());
	}

	// ---------------------------------------------------------------------
	// Token estimation
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("max_tokens 500 -> engine called with 500")
	void estimateFromMaxTokens() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"model\":\"gpt-a\",\"max_tokens\":500}"), filter);
		assertEquals(500, captor.getValue());
	}

	@Test
	@DisplayName("max_completion_tokens 800 (no max_tokens) -> 800")
	void estimateFromMaxCompletionTokens() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"model\":\"gpt-a\",\"max_completion_tokens\":800}"), filter);
		assertEquals(800, captor.getValue());
	}

	@Test
	@DisplayName("no token field -> default 100")
	void estimateDefaults() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"model\":\"gpt-a\"}"), filter);
		assertEquals(KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue());
	}

	@Test
	@DisplayName("max_tokens 0 and -5 -> default 100")
	void estimateNonPositive() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"max_tokens\":0}"), filter);
		assertEquals(KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue());
		verify(engine, times(1)).checkRateLimit(any(), any(), anyInt());
	}

	@Test
	@DisplayName("max_tokens 5_000_000 -> capped at 1_000_000")
	void estimateCapped() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"max_tokens\":5000000}"), filter);
		assertEquals(KeyAuthFilter.MAX_ESTIMATED_TOKENS, captor.getValue());
	}

	@Test
	@DisplayName("non-numeric max_tokens -> default 100")
	void estimateNonNumeric() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"max_tokens\":\"abc\"}"), filter);
		assertEquals(KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue());
	}

	// ---------------------------------------------------------------------
	// Rate-limit decisions: Allowed
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("allowed -> all six headers, owner attribute, chain invoked")
	void allowedHeadersAndAttr() throws Exception {
		stubKeyPresent();
		stubAllowed(10, 8, 1000, 900);
		CachedBodyHttpServletRequest request = request("Bearer " + VALID_KEY, null);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request, response, chain.chain());

		assertEquals(200, response.getStatus());
		assertEquals("10", response.getHeader(KeyAuthFilter.HEADER_LIMIT_RPM));
		assertEquals("8", response.getHeader(KeyAuthFilter.HEADER_REMAINING_RPM));
		assertEquals("1700000000", response.getHeader(KeyAuthFilter.HEADER_RESET_RPM));
		assertEquals("1000", response.getHeader(KeyAuthFilter.HEADER_LIMIT_TPM));
		assertEquals("900", response.getHeader(KeyAuthFilter.HEADER_REMAINING_TPM));
		assertEquals("1700000060", response.getHeader(KeyAuthFilter.HEADER_RESET_TPM));
		assertEquals(OWNER, request.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE));
		assertSame(request, chain.chain().getRequest(), "the same wrapped request must continue down the chain");
	}

	@Test
	@DisplayName("unlimited dimensions -> 'unlimited' header values")
	void unlimitedHeaders() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(0, 0, Set.of(), true)));
		when(engine.checkRateLimit(any(), any(), anyInt())).thenReturn(
				new RateLimitDecision.Allowed(new RateLimitState(
						0, 0, Instant.ofEpochSecond(1_700_000_000),
						0, 0, Instant.ofEpochSecond(1_700_000_060)
				)));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(KeyAuthFilter.UNLIMITED_HEADER_VALUE, response.getHeader(KeyAuthFilter.HEADER_LIMIT_RPM));
		assertEquals("0", response.getHeader(KeyAuthFilter.HEADER_REMAINING_RPM));
		assertEquals(KeyAuthFilter.UNLIMITED_HEADER_VALUE, response.getHeader(KeyAuthFilter.HEADER_LIMIT_TPM));
	}

	// ---------------------------------------------------------------------
	// Rate-limit decisions: Rejected
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("RPM rejected -> 429 with Retry-After, zero remaining, epoch resets, chain not invoked")
	void rejectedRpm() throws Exception {
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), anyInt()))
				.thenReturn(new RateLimitDecision.Rejected(RejectionReason.RPM_EXCEEDED, 45));
		long before = System.currentTimeMillis() / 1000L;
		CachedBodyHttpServletRequest request = request("Bearer " + VALID_KEY, null);
		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(request, response, chain.chain());
		long after = System.currentTimeMillis() / 1000L;

		assertEquals(429, response.getStatus());
		assertEquals("45", response.getHeader("Retry-After"));
		assertEquals("10", response.getHeader(KeyAuthFilter.HEADER_LIMIT_RPM));
		assertEquals("0", response.getHeader(KeyAuthFilter.HEADER_REMAINING_RPM));
		assertEquals("1000", response.getHeader(KeyAuthFilter.HEADER_LIMIT_TPM));
		assertEquals("0", response.getHeader(KeyAuthFilter.HEADER_REMAINING_TPM));
		long reset = Long.parseLong(response.getHeader(KeyAuthFilter.HEADER_RESET_RPM));
		assertTrue(
				reset >= before + 45 && reset <= after + 45 + 1,
				"reset must be ~now+45s, was " + reset
		);
		assertEquals(
				RejectionReason.RPM_EXCEEDED.name(),
				parse(response).get("error").get("code").asString()
		);
		assertNull(chain.chain().getRequest(), "chain must not run for rejected requests");
	}

	@Test
	@DisplayName("TPM rejected -> 429 with Retry-After 2")
	void rejectedTpm() throws Exception {
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), anyInt()))
				.thenReturn(new RateLimitDecision.Rejected(RejectionReason.TPM_EXCEEDED, 2));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		assertEquals(429, response.getStatus());
		assertEquals("2", response.getHeader("Retry-After"));
		assertEquals(
				RejectionReason.TPM_EXCEEDED.name(),
				parse(response).get("error").get("code").asString()
		);
	}

	// ---------------------------------------------------------------------
	// shouldNotFilter gating
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("shouldNotFilter: GET to target path is skipped")
	void shouldNotFilterGet() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
		request.setServletPath(PATH);
		assertTrue(filter.shouldNotFilter(request));
	}

	@Test
	@DisplayName("shouldNotFilter: POST to another path is skipped")
	void shouldNotFilterOtherPath() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/other");
		request.setServletPath("/other");
		assertTrue(filter.shouldNotFilter(request));
	}

	@Test
	@DisplayName("shouldNotFilter: POST to target path is filtered")
	void shouldNotFilterTargetPost() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
		request.setServletPath(PATH);
		assertFalse(filter.shouldNotFilter(request));
	}

	// ---------------------------------------------------------------------
	// Edge-case body parsing
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("model present but null node -> treated as absent; empty allow-list proceeds")
	void modelNullNode() throws Exception {
		stubKeyPresent();
		stubAllowed(10, 9, 1000, 900);
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, "{\"model\":null}"), filter);
		assertEquals(200, response.getStatus());
	}

	@Test
	@DisplayName("model present but non-textual (number) -> treated as absent; empty allow-list proceeds")
	void modelNumericNode() throws Exception {
		stubKeyPresent();
		stubAllowed(10, 9, 1000, 900);
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, "{\"model\":123}"), filter);
		assertEquals(200, response.getStatus());
	}

	@Test
	@DisplayName("max_tokens null falls back to max_completion_tokens")
	void maxTokensNullFallsBackToMaxCompletion() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"max_tokens\":null,\"max_completion_tokens\":800}"), filter);
		assertEquals(800, captor.getValue());
	}

	@Test
	@DisplayName("max_tokens null and max_completion_tokens null -> default estimate")
	void bothTokenFieldsNull() throws Exception {
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		stubKeyPresent();
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		invoke(request("Bearer " + VALID_KEY, "{\"max_tokens\":null,\"max_completion_tokens\":null}"), filter);
		assertEquals(KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue());
	}

	@Test
	@DisplayName("request without the caching wrapper -> body treated as empty; proceeds with defaults")
	void unwrappedRequestBodyReadAsEmpty() throws Exception {
		stubKeyPresent();
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));

		MockHttpServletRequest raw = new MockHttpServletRequest("POST", PATH);
		raw.setServletPath(PATH);
		raw.addHeader("Authorization", "Bearer " + VALID_KEY);
		raw.setContent("{\"model\":\"gpt-a\",\"max_tokens\":700}".getBytes(StandardCharsets.UTF_8));

		MockClientChain chain = new MockClientChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilterInternal(raw, response, chain.chain());

		assertEquals(200, response.getStatus());
		assertEquals(
				KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue(),
				"unwrapped request must be treated as empty body"
		);
		assertSame(raw, chain.chain().getRequest(), "accepted requests must continue down the chain");
	}

	// ---------------------------------------------------------------------
	// Error responses
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("error responses carry JSON content type with UTF-8 charset")
	void errorContentType() throws Exception {
		MockHttpServletResponse response = invoke(request(null, null), filter);
		assertTrue(response.getContentType().contains("application/json"));
		assertTrue(response.getCharacterEncoding().equalsIgnoreCase("UTF-8"));
	}

	@Test
	@DisplayName("403 responses carry the KEY_DISABLED code without leaking key material")
	void disabledKeyBodyHasNoKeyMaterial() throws Exception {
		when(kms.findByHash(any())).thenReturn(Optional.of(key(10, 1000, Set.of(), false)));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, null), filter);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertFalse(body.contains(VALID_KEY), "response must never echo the key");
	}

	@Test
	@DisplayName("rejection messages are defined for every rejection reason")
	void rejectionMessagesForAllReasons() {
		assertEquals(
				"Request rate limit exceeded. Retry after the indicated period.",
				KeyAuthFilter.rejectionMessage(RejectionReason.RPM_EXCEEDED)
		);
		assertEquals(
				"Token rate limit exceeded. Retry after the indicated period.",
				KeyAuthFilter.rejectionMessage(RejectionReason.TPM_EXCEEDED)
		);
		assertEquals("API key is disabled.", KeyAuthFilter.rejectionMessage(RejectionReason.KEY_DISABLED));
		assertEquals("API key not found.", KeyAuthFilter.rejectionMessage(RejectionReason.KEY_NOT_FOUND));
		assertEquals(
				"Model not allowed for this key.",
				KeyAuthFilter.rejectionMessage(RejectionReason.MODEL_NOT_ALLOWED)
		);
	}

	@Test
	@DisplayName("non-object JSON body (array) -> treated as absent model with default estimate")
	void arrayJsonBody() throws Exception {
		stubKeyPresent();
		ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
		when(engine.checkRateLimit(any(), any(), captor.capture())).thenReturn(allowed(10, 9, 1000, 900));
		MockHttpServletResponse response = invoke(request("Bearer " + VALID_KEY, "[1,2,3]"), filter);
		assertEquals(200, response.getStatus());
		assertEquals(KeyAuthFilter.DEFAULT_ESTIMATED_TOKENS, captor.getValue());
	}

	@Test
	@DisplayName("isWellFormedKey rejects null")
	void isWellFormedKeyRejectsNull() {
		assertFalse(KeyAuthFilter.isWellFormedKey(null));
		assertTrue(KeyAuthFilter.isWellFormedKey(VALID_KEY));
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static RateLimitDecision allowed(int rl, int rr, int tl, int tr) {
		return new RateLimitDecision.Allowed(new RateLimitState(
				rl, rr, Instant.ofEpochSecond(1_700_000_000), tl, tr, Instant.ofEpochSecond(1_700_000_060)));
	}

	/**
	 * Chain wrapper exposing the stored request for assertions.
	 */
	private static final class MockClientChain {

		private final MockFilterChain chain = new MockFilterChain();

		MockFilterChain chain() {
			return chain;
		}
	}
}