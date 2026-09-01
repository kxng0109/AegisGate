package io.github.kxng0109.aegisgate.cache.engine;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-retrieval verification guardrails preventing semantic false-positives, entity swaps, and polarity/intent
 * reversals in L2 semantic vector search.
 */
@Component
public class CacheGuardrails {

	private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
	private static final Pattern CAPITALIZED_ENTITY_PATTERN = Pattern.compile("\\b[A-Z][a-zA-Z0-9_\\-]{2,}\\b");

	private static final Set<String> COMMON_STOP_WORDS = Set.of(
			"THE", "WHAT", "HOW", "CAN", "TELL", "PLEASE", "WHY", "WHEN", "WHERE", "WHO",
			"WHICH", "COULD", "WOULD", "SHOULD", "THERE", "HERE", "THIS", "THAT", "THESE", "THOSE",
			"CALCULATE", "IS", "ARE", "WAS", "WERE", "DO", "DOES", "DID", "EXPLAIN", "SHOW", "GIVE"
	);

	private static final List<PolarityPair> POLARITY_PAIRS = List.of(
			new PolarityPair("enable", "disable"),
			new PolarityPair("enabled", "disabled"),
			new PolarityPair("enabling", "disabling"),
			new PolarityPair("turn on", "turn off"),
			new PolarityPair("start", "stop"),
			new PolarityPair("create", "delete"),
			new PolarityPair("add", "remove"),
			new PolarityPair("insert", "drop"),
			new PolarityPair("true", "false"),
			new PolarityPair("with", "without"),
			new PolarityPair("increase", "decrease")
	);

	private static final Set<String> NEGATION_TERMS = Set.of(
			"not", "never", "no", "neither", "nor", "none", "n't", "cannot", "cant", "without"
	);

	private record PolarityPair(String positive, String negative) {
	}

	/**
	 * Validates whether a candidate cached prompt is semantically safe to serve for the incoming prompt.
	 *
	 * @param incomingPrompt       user prompt from the active client request
	 * @param cachedPrompt         original prompt stored in the cached entry
	 * @param polarityGuardEnabled whether polarity and negation checking is active
	 * @param entityGuardEnabled   whether entity and number intersection checking is active
	 * @return true if the candidate passes all active guardrails
	 */
	public boolean validateSemanticMatch(
			String incomingPrompt,
			String cachedPrompt,
			boolean polarityGuardEnabled,
			boolean entityGuardEnabled
	) {
		if (polarityGuardEnabled && !checkPolarityMatch(incomingPrompt, cachedPrompt)) {
			return false;
		}
		if (entityGuardEnabled && !checkEntityMatch(incomingPrompt, cachedPrompt)) {
			return false;
		}
		return true;
	}

	/**
	 * Checks that the incoming and cached prompts share consistent polarity and negation intent.
	 *
	 * @param incomingPrompt active user prompt
	 * @param cachedPrompt   cached entry prompt
	 * @return true if polarity matches, false if polarity is inverted
	 */
	public boolean checkPolarityMatch(String incomingPrompt, String cachedPrompt) {
		String inLower = incomingPrompt.toLowerCase(Locale.ROOT);
		String cachedLower = cachedPrompt.toLowerCase(Locale.ROOT);

		// 1. Check opposing polarity pairs (e.g. enable vs disable)
		for (PolarityPair pair : POLARITY_PAIRS) {
			boolean inHasPos = inLower.contains(pair.positive());
			boolean inHasNeg = inLower.contains(pair.negative());
			boolean cachedHasPos = cachedLower.contains(pair.positive());
			boolean cachedHasNeg = cachedLower.contains(pair.negative());

			if ((inHasPos && cachedHasNeg) || (inHasNeg && cachedHasPos)) {
				return false;
			}
		}

		// 2. Check general negation presence
		boolean inHasNegation = hasNegationTerm(inLower);
		boolean cachedHasNegation = hasNegationTerm(cachedLower);

		return inHasNegation == cachedHasNegation;
	}

	/**
	 * Checks that numbers and proper noun entities in both prompts do not conflict.
	 *
	 * @param incomingPrompt active user prompt
	 * @param cachedPrompt   cached entry prompt
	 * @return true if entities and numbers match or are safely compatible, false on conflicts
	 */
	public boolean checkEntityMatch(String incomingPrompt, String cachedPrompt) {
		// 1. Exact numbers check
		Set<String> inNumbers = extractNumbers(incomingPrompt);
		Set<String> cachedNumbers = extractNumbers(cachedPrompt);
		if (!inNumbers.isEmpty() || !cachedNumbers.isEmpty()) {
			if (!inNumbers.equals(cachedNumbers)) {
				return false;
			}
		}

		// 2. Capitalized entity intersection check
		Set<String> inEntities = extractEntities(incomingPrompt);
		Set<String> cachedEntities = extractEntities(cachedPrompt);
		if (!inEntities.isEmpty() || !cachedEntities.isEmpty()) {
			if (!inEntities.equals(cachedEntities)) {
				return false;
			}
		}

		return true;
	}

	private boolean hasNegationTerm(String lower) {
		for (String term : NEGATION_TERMS) {
			if (lower.matches(".*\\b" + Pattern.quote(term) + "\\b.*")) {
				return true;
			}
		}
		return false;
	}

	private Set<String> extractNumbers(String text) {
		Set<String> numbers = new HashSet<>();
		Matcher matcher = NUMBER_PATTERN.matcher(text);
		while (matcher.find()) {
			numbers.add(matcher.group());
		}
		return numbers;
	}

	private Set<String> extractEntities(String text) {
		Set<String> entities = new HashSet<>();
		Matcher matcher = CAPITALIZED_ENTITY_PATTERN.matcher(text);
		while (matcher.find()) {
			String entity = matcher.group();
			if (!COMMON_STOP_WORDS.contains(entity.toUpperCase(Locale.ROOT))) {
				entities.add(entity);
			}
		}
		return entities;
	}
}
