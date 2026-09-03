package io.github.kxng0109.aegisgate.security.guardrail.injection;

import io.github.kxng0109.aegisgate.security.guardrail.common.ConfusablesFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-throughput multi-tier prompt injection and jailbreak screening engine.
 *
 * <p>Tier 0 normalizes Unicode homoglyphs (UTS #39) and evaluates fast heuristic DFAs (&lt;3µs).
 * Tier 1 evaluates structural anomalies (delimiter density, ChatML header attacks) (&lt;10µs).</p>
 */
@Component
public class PromptInjectionScanner {

	private static final List<InjectionRule> TIER_0_RULES = List.of(
			new InjectionRule(
					"INSTRUCTION_OVERRIDE",
					Pattern.compile(
							"(?i)\\b(?:ignore|disregard|forget|bypass|override)\\s+(?:all\\s+)?(?:previous|prior|above|system)\\s+(?:instructions|prompts|rules|commands|directives)\\b"),
					0.95,
					"System instruction override attempt detected"
			),
			new InjectionRule(
					"PERSONA_SIMULATION_JAILBREAK",
					Pattern.compile(
							"(?i)\\b(?:dan\\s+mode|do\\s+anything\\s+now|aim\\s+mode|jailbroken\\s+version|developer\\s+mode\\s+enabled|unrestricted\\s+mode|always\\s+say\\s+yes)\\b"),
					0.98,
					"Adversarial persona simulation / jailbreak attempt detected"
			),
			new InjectionRule(
					"DELIMITER_INJECTION",
					Pattern.compile(
							"(?i)(?:<\\|im_start\\|>|<\\|im_end\\|>|<\\|start_header_id\\|>|<\\|end_header_id\\|>|\\[SYSTEM\\]|```system)"),
					0.99,
					"Delimiter or control token injection detected"
			),
			new InjectionRule(
					"FAKE_TOOL_CALL",
					Pattern.compile("(?i)(?:<tool_call>|<function_call>|call:default_api)"),
					0.90,
					"Synthetic tool execution injection detected"
			)
	);

	/**
	 * Scans the prompt text for adversarial prompt injection or jailbreak patterns.
	 *
	 * @param rawText original prompt text
	 * @return scan result indicating whether injection was identified
	 */
	public InjectionScanResult scan(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return InjectionScanResult.clean();
		}

		// Tier 0: Confusables normalization and DFA matching
		String normalized = ConfusablesFilter.normalize(rawText);

		for (InjectionRule rule : TIER_0_RULES) {
			Matcher matcher = rule.pattern().matcher(normalized);
			if (matcher.find()) {
				return new InjectionScanResult(
						true,
						rule.category(),
						matcher.group(),
						rule.confidence(),
						rule.description()
				);
			}
		}

		// Tier 1: Structural anomaly detection (e.g. excessive delimiter injection)
		if (detectStructuralAnomaly(normalized)) {
			return new InjectionScanResult(
					true,
					"STRUCTURAL_ANOMALY",
					"excessive_delimiter_sequence",
					0.85,
					"Abnormal concentration of control delimiters or markdown blocks"
			);
		}

		return InjectionScanResult.clean();
	}

	private boolean detectStructuralAnomaly(String text) {
		int backtickRuns = 0;
		int idx = 0;
		while ((idx = text.indexOf("```", idx)) != -1) {
			backtickRuns++;
			idx += 3;
			if (backtickRuns >= 10) {
				return true; // Abnormally high code-block delimiter clustering
			}
		}
		return false;
	}

	private record InjectionRule(String category, Pattern pattern, double confidence, String description) {
	}
}
