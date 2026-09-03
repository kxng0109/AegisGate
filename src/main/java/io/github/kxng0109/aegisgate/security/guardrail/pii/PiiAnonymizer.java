package io.github.kxng0109.aegisgate.security.guardrail.pii;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anonymizes PII within prompts by substituting cleartext entities with semantic surrogate tokens and storing the
 * reversible mappings in the request-scoped {@link EphemeralPiiVault}.
 */
@Component
public class PiiAnonymizer {

	private final PiiScanner piiScanner;

	public PiiAnonymizer(PiiScanner piiScanner) {
		this.piiScanner = piiScanner;
	}

	/**
	 * Scans the input text, generates semantic surrogate tokens, registers them in the vault, and returns the
	 * anonymized prompt.
	 *
	 * @param text  input prompt text
	 * @param vault request-scoped ephemeral vault
	 * @return text with all verified PII replaced with surrogate tokens
	 */
	public String anonymize(String text, EphemeralPiiVault vault) {
		if (text == null || text.isBlank() || vault == null) {
			return text;
		}

		List<PiiEntity> entities = piiScanner.scan(text);
		if (entities.isEmpty()) {
			return text;
		}

		Map<PiiType, Integer> counters = new HashMap<>();
		StringBuilder result = new StringBuilder(text);

		// Process in reverse offset order so earlier indices remain valid
		for (int i = entities.size() - 1; i >= 0; i--) {
			PiiEntity entity = entities.get(i);
			String raw = entity.originalValue();

			String surrogate = vault.getExistingSurrogate(raw);
			if (surrogate == null) {
				int nextIdx = counters.compute(entity.type(), (k, v) -> (v == null ? 1 : v + 1));
				surrogate = entity.type().getSurrogatePrefix() + nextIdx + ">";
				vault.store(surrogate, raw);
			}

			result.replace(entity.startOffset(), entity.endOffset(), surrogate);
		}

		return result.toString();
	}
}
