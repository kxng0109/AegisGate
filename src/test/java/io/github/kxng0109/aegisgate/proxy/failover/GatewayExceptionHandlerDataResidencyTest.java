package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.security.compliance.DataResidencyBreachException;
import io.github.kxng0109.aegisgate.security.compliance.Jurisdiction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayExceptionHandler Data Residency Tests")
class GatewayExceptionHandlerDataResidencyTest {

	private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

	@Test
	@DisplayName("handleDataResidencyBreach maps to HTTP 503 with DATA_SOVEREIGNTY_VIOLATION code")
	void mapsDataResidencyBreachTo503() {
		DataResidencyBreachException exception = new DataResidencyBreachException(Jurisdiction.NG, "gpt-4o");
		ResponseEntity<Map<String, Object>> response = handler.handleDataResidencyBreach(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();

		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		assertThat(error)
				.containsEntry("code", "DATA_SOVEREIGNTY_VIOLATION")
				.containsKey("message");
		assertThat((String) error.get("message")).contains("designated sovereign zone [NG]");
	}
}
