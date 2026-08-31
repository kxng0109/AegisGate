package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CircuitStateResponse;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreaker;
import io.github.kxng0109.aegisgate.proxy.failover.CircuitBreakerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AdminCircuitController")
@SuppressWarnings("DataFlowIssue")
class AdminCircuitControllerTest {

	private final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
	private final AdminCircuitController controller = new AdminCircuitController(factory);

	@Test
	@DisplayName("listCircuits returns all provider circuit states sorted")
	void listCircuitsSuccess() {
		when(factory.states()).thenReturn(Map.of(
				"openai", CircuitBreaker.State.CLOSED,
				"anthropic", CircuitBreaker.State.OPEN
		));

		ResponseEntity<List<CircuitStateResponse>> response = controller.listCircuits();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(2);
		assertThat(response.getBody().getFirst().provider()).isEqualTo("anthropic");
		assertThat(response.getBody().getFirst().state()).isEqualTo("OPEN");
		assertThat(response.getBody().get(1).provider()).isEqualTo("openai");
		assertThat(response.getBody().get(1).state()).isEqualTo("CLOSED");
	}

	@Test
	@DisplayName("getCircuit returns 200 OK for known provider and 404 for unknown")
	void getCircuitScenarios() {
		CircuitBreaker breaker = mock(CircuitBreaker.class);
		when(breaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

		when(factory.providerNames()).thenReturn(Set.of("openai"));
		when(factory.get("openai")).thenReturn(breaker);

		ResponseEntity<CircuitStateResponse> found = controller.getCircuit("openai");
		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody()).isNotNull();
		assertThat(found.getBody().provider()).isEqualTo("openai");
		assertThat(found.getBody().state()).isEqualTo("CLOSED");

		ResponseEntity<CircuitStateResponse> notFound = controller.getCircuit("unknown-provider");
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("resetCircuit returns 200 OK for known provider and 404 for unknown")
	void resetCircuitScenarios() {
		when(factory.providerNames()).thenReturn(Set.of("openai"));
		when(factory.reset("openai")).thenReturn(CircuitBreaker.State.CLOSED);

		ResponseEntity<CircuitStateResponse> reset = controller.resetCircuit("openai");
		assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reset.getBody()).isNotNull();
		assertThat(reset.getBody().provider()).isEqualTo("openai");
		assertThat(reset.getBody().state()).isEqualTo("CLOSED");

		ResponseEntity<CircuitStateResponse> notFound = controller.resetCircuit("unknown-provider");
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
