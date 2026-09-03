package io.github.kxng0109.aegisgate.security.guardrail.injection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromptInjectionException Tests")
class PromptInjectionExceptionTest {

	@Test
	@DisplayName("builds RFC 9457 ProblemDetail with category, pattern, and risk score")
	void exceptionAndProblemDetailMapping() {
		InjectionScanResult result = new InjectionScanResult(
				true,
				"INSTRUCTION_OVERRIDE",
				"ignore all previous instructions",
				0.95,
				"System instruction override attempt detected"
		);

		PromptInjectionException ex = new PromptInjectionException(result);
		assertThat(ex.getResult()).isSameAs(result);
		assertThat(ex.getMessage()).contains("INSTRUCTION_OVERRIDE").contains("0.95");

		ProblemDetail problem = ex.toProblemDetail("/v1/chat/completions");
		assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(problem.getType()).isEqualTo(URI.create("https://aegisgate.io/errors/prompt-injection-detected"));
		assertThat(problem.getTitle()).isEqualTo("Unprocessable Content - Prompt Injection Detected");
		assertThat(problem.getInstance()).isEqualTo(URI.create("/v1/chat/completions"));
		assertThat(problem.getDetail()).contains("System instruction override attempt detected");
		assertThat(problem.getProperties())
				.containsEntry("category", "INSTRUCTION_OVERRIDE")
				.containsEntry("matched_pattern", "ignore all previous instructions")
				.containsEntry("risk_score", 0.95);
	}
}
