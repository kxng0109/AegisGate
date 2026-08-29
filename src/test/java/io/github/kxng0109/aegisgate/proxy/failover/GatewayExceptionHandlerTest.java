package io.github.kxng0109.aegisgate.proxy.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GatewayExceptionHandler}: the mapping of the failover exception to 502, 503, 504, and the
 * passthrough of a specific upstream status, always with generic client safe messages inside the standard error
 * envelope.
 */
@DisplayName("GatewayExceptionHandler")
class GatewayExceptionHandlerTest {

	private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

	@Test
	@DisplayName("generic failure maps to 502 Bad Gateway")
	void mapsGenericFailureTo502() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("all failed", null, false, false));

		assertEquals(502, response.getStatusCode().value());
		assertEquals("no upstream provider could serve this request", errorMessage(response));
	}

	@Test
	@DisplayName("service unavailable maps to 503")
	void mapsUnavailableTo503() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(
						new UpstreamUnavailableException("nothing reachable", null, true, false));

		assertEquals(503, response.getStatusCode().value());
		assertEquals("upstream service unavailable", errorMessage(response));
	}

	@Test
	@DisplayName("timeout maps to 504 Gateway Timeout")
	void mapsTimeoutTo504() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("slow", null, false, true));

		assertEquals(504, response.getStatusCode().value());
		assertEquals("upstream request timed out", errorMessage(response));
	}

	@Test
	@DisplayName("an explicit upstream status is passed through")
	void passesThroughUpstreamStatus() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(
						new UpstreamUnavailableException("rejected", null, false, false, 401));

		assertEquals(401, response.getStatusCode().value());
		assertEquals("the upstream provider rejected the request", errorMessage(response));
	}

	@Test
	@DisplayName("timeout wins over the low priority flags when both are set")
	void timeoutTakesPrecedenceOverUnavailable() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("mixed", null, true, true));

		assertEquals(504, response.getStatusCode().value());
	}

	@SuppressWarnings("unchecked")
	private static String errorMessage(ResponseEntity<Map<String, Object>> response) {
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		return (String) error.get("message");
	}
}