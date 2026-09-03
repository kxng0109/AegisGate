package io.github.kxng0109.aegisgate.security.guardrail.secret;

import java.util.regex.Pattern;

/**
 * Definition of an ingress secret/credential detection rule.
 *
 * @param id           unique identifier of the rule (e.g., "openai-project-key")
 * @param description  human-readable description
 * @param prefixAnchor fast static anchor prefix for Stage 1 byte-level scanning
 * @param pattern      exact DFA regular expression for Stage 2 boundary matching
 * @param minEntropy   minimum Shannon information entropy threshold
 * @param checkEntropy whether entropy verification is required
 * @param checkLuhn    whether Luhn mod-10 verification is required
 */
public record SecretRule(
		String id,
		String description,
		String prefixAnchor,
		Pattern pattern,
		double minEntropy,
		boolean checkEntropy,
		boolean checkLuhn
) {
}
