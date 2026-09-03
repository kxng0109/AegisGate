package io.github.kxng0109.aegisgate.security.guardrail.injection;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * Exception thrown when an ingress request contains an adversarial prompt injection or jailbreak attempt. Maps directly
 * to RFC 9457 {@link ProblemDetail} with HTTP 422 Unprocessable Entity.
 */
public class PromptInjectionException extends RuntimeException {

	private static final URI PROBLEM_TYPE = URI.create("https://aegisgate.io/errors/prompt-injection-detected");

	private final InjectionScanResult result;

	public PromptInjectionException(InjectionScanResult result) {
		super("Prompt injection detected: category=" + result.category() + ", risk=" + result.riskScore());
		this.result = result;
	}

	public InjectionScanResult getResult() {
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
				HttpStatus.UNPROCESSABLE_ENTITY,
				"The prompt contains patterns indicative of adversarial prompt injection (" + result.detail()
						+ "). Transmission was blocked by policy."
		);
		problem.setType(PROBLEM_TYPE);
		problem.setTitle("Unprocessable Content - Prompt Injection Detected");
		problem.setInstance(URI.create(instancePath));
		problem.setProperty("category", result.category());
		problem.setProperty("matched_pattern", result.matchedPattern());
		problem.setProperty("risk_score", result.riskScore());
		return problem;
	}
}
