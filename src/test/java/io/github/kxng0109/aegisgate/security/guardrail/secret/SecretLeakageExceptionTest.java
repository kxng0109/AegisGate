package io.github.kxng0109.aegisgate.security.guardrail.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretLeakageException & SecretScanResult Tests")
class SecretLeakageExceptionTest {

	@Test
	@DisplayName("SecretScanResult.clean() creates clean result record")
	void cleanScanResult() {
		SecretScanResult clean = SecretScanResult.clean();
		assertThat(clean.detected()).isFalse();
		assertThat(clean.ruleId()).isNull();
		assertThat(clean.description()).isNull();
		assertThat(clean.maskedToken()).isNull();
		assertThat(clean.tokenFingerprint()).isNull();
		assertThat(clean.jsonPath()).isNull();
	}

	@Test
	@DisplayName("SecretLeakageException constructs exception and builds RFC 9457 ProblemDetail")
	void exceptionAndProblemDetailMapping() {
		SecretScanResult result = new SecretScanResult(
				true,
				"openai-project-key",
				"OpenAI Project Key",
				"sk-proj-********",
				"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
				"/messages/0/content"
		);

		SecretLeakageException ex = new SecretLeakageException(result);
		assertThat(ex.getResult()).isSameAs(result);
		assertThat(ex.getMessage()).contains("openai-project-key").contains(result.tokenFingerprint());

		ProblemDetail problem = ex.toProblemDetail("/v1/chat/completions");
		assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(problem.getType()).isEqualTo(URI.create("https://aegisgate.io/errors/credential-leakage-detected"));
		assertThat(problem.getTitle()).isEqualTo("Unprocessable Content - Secret or Credential Detected");
		assertThat(problem.getInstance()).isEqualTo(URI.create("/v1/chat/completions"));
		assertThat(problem.getDetail()).contains("OpenAI Project Key");
		assertThat(problem.getProperties())
				.containsEntry("rule_id", "openai-project-key")
				.containsEntry("masked_token", "sk-proj-********")
				.containsEntry("token_fingerprint", result.tokenFingerprint())
				.containsEntry("json_path", "/messages/0/content");
	}
}
