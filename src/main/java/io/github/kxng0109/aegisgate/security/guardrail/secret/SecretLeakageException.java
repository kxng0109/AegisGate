package io.github.kxng0109.aegisgate.security.guardrail.secret;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * Exception thrown when an ingress request contains an unauthorized secret or credential. Maps directly to RFC 9457
 * {@link ProblemDetail} with HTTP 422 Unprocessable Entity.
 */
public class SecretLeakageException extends RuntimeException {

	private static final URI PROBLEM_TYPE = URI.create("https://aegisgate.io/errors/credential-leakage-detected");

	private final SecretScanResult result;

	public SecretLeakageException(SecretScanResult result) {
		super("Ingress secret leakage detected: rule=" + result.ruleId() + ", fingerprint="
				      + result.tokenFingerprint());
		this.result = result;
	}

	public SecretScanResult getResult() {
		return result;
	}

	/**
	 * Builds an RFC 9457 compliant ProblemDetail object for serialization as application/problem+json.
	 *
	 * @param instancePath request URI instance
	 * @return ProblemDetail model
	 */
	public ProblemDetail toProblemDetail(String instancePath) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_CONTENT,
				"Request body contains an active secret or credential (" + result.description()
						+ "). Transmission was blocked to prevent token exposure."
		);
		problem.setType(PROBLEM_TYPE);
		problem.setTitle("Unprocessable Content - Secret or Credential Detected");
		problem.setInstance(URI.create(instancePath));
		problem.setProperty("rule_id", result.ruleId());
		problem.setProperty("masked_token", result.maskedToken());
		problem.setProperty("token_fingerprint", result.tokenFingerprint());
		problem.setProperty("json_path", result.jsonPath());
		return problem;
	}
}
