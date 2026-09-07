package io.github.kxng0109.aegisgate.cache.engine;

import org.springframework.stereotype.Component;

import java.util.*;
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
	 * <p>Uses slot-aligned contradiction detection: values occupying the same grammatical slot (derived from
	 * the immediately-preceding context word) must overlap; disjoint values in a shared slot indicate a
	 * conflict (e.g. "CEO of Apple" vs "CEO of Microsoft"). Compatible prompts with no shared slot
	 * (e.g. "Mentions France" vs "all lowercase") pass. If exactly one side carries numbers or entities,
	 * the match is rejected to avoid serving a specific answer for a generic prompt or vice versa.</p>
	 *
	 * @param incomingPrompt active user prompt
	 * @param cachedPrompt   cached entry prompt
	 * @return true if entities and numbers match or are safely compatible, false on conflicts
	 */
	public boolean checkEntityMatch(String incomingPrompt, String cachedPrompt) {
		// 1. Slot-aligned number contradiction check
		Map<String, Set<String>> inNumberSlots = extractSlottedNumbers(incomingPrompt);
		Map<String, Set<String>> cachedNumberSlots = extractSlottedNumbers(cachedPrompt);
		if (!inNumberSlots.isEmpty() || !cachedNumberSlots.isEmpty()) {
			if (inNumberSlots.isEmpty() || cachedNumberSlots.isEmpty()) {
				return false;
			}
			if (hasSlotContradiction(inNumberSlots, cachedNumberSlots)) {
				return false;
			}
		}

		// 2. Slot-aligned entity contradiction check
		Map<String, Set<String>> inEntitySlots = extractSlottedEntities(incomingPrompt);
		Map<String, Set<String>> cachedEntitySlots = extractSlottedEntities(cachedPrompt);
		if (!inEntitySlots.isEmpty() || !cachedEntitySlots.isEmpty()) {
			if (inEntitySlots.isEmpty() || cachedEntitySlots.isEmpty()) {
				return false;
			}
			if (hasSlotContradiction(inEntitySlots, cachedEntitySlots)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Returns true if any shared slot holds disjoint value sets (a genuine entity/number swap).
	 */
	private boolean hasSlotContradiction(Map<String, Set<String>> incoming, Map<String, Set<String>> cached) {
		for (Map.Entry<String, Set<String>> entry : incoming.entrySet()) {
			Set<String> cachedValues = cached.get(entry.getKey());
			if (cachedValues == null) {
				continue;
			}
			boolean overlaps = false;
			for (String value : entry.getValue()) {
				if (cachedValues.contains(value)) {
					overlaps = true;
					break;
				}
			}
			if (!overlaps) {
				return true;
			}
		}
		return false;
	}

	private boolean hasNegationTerm(String lower) {
		for (String term : NEGATION_TERMS) {
			if (lower.matches(".*\\b" + Pattern.quote(term) + "\\b.*")) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Set<String>> extractSlottedNumbers(String text) {
		Map<String, Set<String>> slots = new HashMap<>();
		Matcher matcher = NUMBER_PATTERN.matcher(text);
		while (matcher.find()) {
			String slot = precedingWordSlot(text, matcher.start()) + ":NUM";
			slots.computeIfAbsent(slot, k -> new HashSet<>()).add(matcher.group());
		}
		return slots;
	}

	private Map<String, Set<String>> extractSlottedEntities(String text) {
		Map<String, Set<String>> slots = new HashMap<>();
		Matcher matcher = CAPITALIZED_ENTITY_PATTERN.matcher(text);
		while (matcher.find()) {
			String entity = matcher.group();
			if (!COMMON_STOP_WORDS.contains(entity.toUpperCase(Locale.ROOT))) {
				String slot = precedingWordSlot(text, matcher.start()) + ":ENT";
				slots.computeIfAbsent(slot, k -> new HashSet<>()).add(entity);
			}
		}
		return slots;
	}

	/**
	 * Derives the grammatical slot key from the word immediately preceding the match start. Articles ({@code the},
	 * {@code a}, {@code an}) are skipped because they precede too many unrelated entities ("the CEO" vs "the Apple"
	 * share "the" but are not a swap); the slot falls back to the nearest preceding content word. Returns the empty
	 * string for matches at the start of the text.
	 */
	private static final Set<String> SLOT_SKIP_WORDS = Set.of("the", "a", "an");

	private static String precedingWordSlot(String text, int matchStart) {
		int end = matchStart;
		while (true) {
			while (end > 0 && !Character.isLetterOrDigit(text.charAt(end - 1))) {
				end--;
			}
			int start = end;
			while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
				start--;
			}
			if (start >= end) {
				return "";
			}
			String word = text.substring(start, end).toLowerCase(Locale.ROOT);
			if (!SLOT_SKIP_WORDS.contains(word)) {
				return word;
			}
			end = start;
		}
	}
}
