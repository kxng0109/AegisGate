package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CircuitStateResponse;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreaker;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreakerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin - Circuit Breakers", description = "Inspecting real-time circuit breaker states and force-resetting upstream providers")
public class AdminCircuitController {

	private final CircuitBreakerFactory circuitBreakerFactory;

	/**
	 * Lists the current circuit breaker states of all configured upstream providers.
	 *
	 * @return HTTP 200 OK with list of provider circuit states
	 */
	@Operation(
			summary = "List provider circuit breaker states",
			description = "Inspects real-time circuit breaker states (`CLOSED`, `OPEN`, `HALF_OPEN`) across all configured upstream LLM providers.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "List of circuit breaker states retrieved",
					content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CircuitStateResponse.class)))
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
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
	@Operation(
			summary = "Get circuit breaker state for provider",
			description = "Retrieves the real-time circuit breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`) for a specific provider.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Circuit state retrieved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CircuitStateResponse.class))),
			@ApiResponse(responseCode = "404", description = "Provider not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/{provider}")
	public ResponseEntity<CircuitStateResponse> getCircuit(
			@Parameter(description = "Configured provider identifier (e.g. openai, anthropic, ollama)", example = "openai")
			@PathVariable("provider") String provider
	) {
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
	@Operation(
			summary = "Force-reset provider circuit breaker",
			description = "Immediately transitions the specified provider's circuit breaker back to `CLOSED` state and resets failure counters.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Circuit breaker reset to CLOSED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CircuitStateResponse.class))),
			@ApiResponse(responseCode = "404", description = "Provider not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping("/{provider}/reset")
	public ResponseEntity<CircuitStateResponse> resetCircuit(
			@Parameter(description = "Configured provider identifier (e.g. openai, anthropic, ollama)", example = "openai")
			@PathVariable("provider") String provider
	) {
		if (!circuitBreakerFactory.providerNames().contains(provider)) {
			return ResponseEntity.notFound().build();
		}
		CircuitBreaker.State state = circuitBreakerFactory.reset(provider);
		return ResponseEntity.ok(new CircuitStateResponse(provider, state.name()));
	}
}

