package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.compliance.DataResidencyBreachException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link UpstreamUnavailableException} and compliance exceptions to client facing status codes.
 *
 * <p>The mapping rules, aligned with RFC 9110:</p>
 * <ul>
 *   <li>a specific upstream status (for example 401 or 403) is passed through,
 *       because a client or key problem cannot be fixed by any provider;</li>
 *   <li>504 when at least one attempt timed out;</li>
 *   <li>503 when nothing usable was reachable (all circuits open, nothing
 *       configured, or a blocked target);</li>
 *   <li>502 in every other all providers failed case;</li>
 *   <li>503 for DataResidencyBreachException when sovereignty prevents failover.</li>
 * </ul>
 *
 * <p>Responses carry only generic messages so internal details never reach
 * the client.</p>
 */
@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler {

	/**
	 * Handles data residency and sovereignty policy breach exceptions.
	 *
	 * @param exception the data residency violation
	 * @return HTTP 503 response with DATA_SOVEREIGNTY_VIOLATION payload
	 */
	@ExceptionHandler(DataResidencyBreachException.class)
	public ResponseEntity<Map<String, Object>> handleDataResidencyBreach(DataResidencyBreachException exception) {
		log.warn("Data sovereignty violation: {}", exception.getMessage());
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", "DATA_SOVEREIGNTY_VIOLATION");
		error.put("message", exception.getMessage());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
	}

	/**
	 * Handles an upstream failure surfaced by the failover orchestrator.
	 *
	 * @param exception the upstream failure
	 * @return a JSON error response with the mapped status
	 */
	@ExceptionHandler(UpstreamUnavailableException.class)
	public ResponseEntity<Map<String, Object>> handleUpstreamUnavailable(UpstreamUnavailableException exception) {
		HttpStatus status = resolveStatus(exception);
		log.warn(
				"Upstream request failed with mapped status {}: {}",
				status.value(), exception.getMessage()
		);
		return ResponseEntity.status(status).body(errorBody(messageFor(status)));
	}

	private HttpStatus resolveStatus(UpstreamUnavailableException exception) {
		int upstreamStatus = exception.getUpstreamStatus();
		if (upstreamStatus >= 400) {
			return HttpStatus.valueOf(upstreamStatus);
		}
		if (exception.isTimedOut()) {
			return HttpStatus.GATEWAY_TIMEOUT;
		}
		if (exception.isServiceUnavailable()) {
			return HttpStatus.SERVICE_UNAVAILABLE;
		}
		return HttpStatus.BAD_GATEWAY;
	}

	private String messageFor(HttpStatus status) {
		if (status == HttpStatus.BAD_GATEWAY) {
			return "no upstream provider could serve this request";
		}
		if (status == HttpStatus.SERVICE_UNAVAILABLE) {
			return "upstream service unavailable";
		}
		if (status == HttpStatus.GATEWAY_TIMEOUT) {
			return "upstream request timed out";
		}
		return "the upstream provider rejected the request";
	}

	private Map<String, Object> errorBody(String message) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("message", message);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		return body;
	}
}