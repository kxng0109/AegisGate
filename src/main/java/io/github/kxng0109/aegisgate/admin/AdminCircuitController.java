package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CircuitStateResponse;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreaker;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreakerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * REST controller for inspecting and resetting upstream circuit breakers under {@code /v1/admin/circuits}.
 */
@RestController
@RequestMapping("/v1/admin/circuits")
@RequiredArgsConstructor
public class AdminCircuitController {

	private final CircuitBreakerFactory circuitBreakerFactory;

	/**
	 * Lists the current circuit breaker states of all configured upstream providers.
	 *
	 * @return HTTP 200 OK with list of provider circuit states
	 */
	@GetMapping
	public ResponseEntity<List<CircuitStateResponse>> listCircuits() {
		Map<String, CircuitBreaker.State> states = circuitBreakerFactory.states();
		List<CircuitStateResponse> response = states.entrySet().stream()
		                                            .map(e -> new CircuitStateResponse(e.getKey(), e.getValue().name()))
		                                            .sorted(Comparator.comparing(CircuitStateResponse::provider))
		                                            .toList();
		return ResponseEntity.ok(response);
	}

	/**
	 * Retrieves the circuit breaker state for a specific upstream provider.
	 *
	 * @param provider provider name
	 * @return HTTP 200 OK with provider circuit state, or HTTP 404 Not Found
	 */
	@GetMapping("/{provider}")
	public ResponseEntity<CircuitStateResponse> getCircuit(@PathVariable("provider") String provider) {
		if (!circuitBreakerFactory.providerNames().contains(provider)) {
			return ResponseEntity.notFound().build();
		}
		CircuitBreaker.State state = circuitBreakerFactory.get(provider).getState();
		return ResponseEntity.ok(new CircuitStateResponse(provider, state.name()));
	}

	/**
	 * Force-resets an upstream provider's circuit breaker to CLOSED.
	 *
	 * @param provider provider name
	 * @return HTTP 200 OK with updated provider circuit state, or HTTP 404 Not Found
	 */
	@PostMapping("/{provider}/reset")
	public ResponseEntity<CircuitStateResponse> resetCircuit(@PathVariable("provider") String provider) {
		if (!circuitBreakerFactory.providerNames().contains(provider)) {
			return ResponseEntity.notFound().build();
		}
		CircuitBreaker.State state = circuitBreakerFactory.reset(provider);
		return ResponseEntity.ok(new CircuitStateResponse(provider, state.name()));
	}
}
