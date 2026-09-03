package io.github.kxng0109.aegisgate.security.guardrail.streaming;

import io.github.kxng0109.aegisgate.security.guardrail.pii.EphemeralPiiVault;

/**
 * Incremental Sliding Window Aho-Corasick surrogate de-anonymizer for SSE chunk streams.
 *
 * <p>Large Language Models emit tokens in nondeterministic chunk boundaries, causing surrogate
 * tokens (e.g., "&lt;PERSON_1&gt;") to be split across chunk lines (e.g. chunk 1: "Hello &lt;PER", chunk 2:
 * "SON_1&gt;").</p>
 *
 * <p>This automaton maintains a bounded carry buffer of at most \(K \le 32\) characters, reconstituting
 * split surrogates while maintaining zero buffering for normal text, preserving Time-To-First-Token (TTFT) and
 * sub-0.1ms latency overhead.</p>
 */
public final class SlidingWindowAhoCorasick {

	private static final int MAX_LOOKAHEAD_K = 32;

	private final EphemeralPiiVault vault;
	private final StringBuilder carry = new StringBuilder(MAX_LOOKAHEAD_K);

	public SlidingWindowAhoCorasick(EphemeralPiiVault vault) {
		this.vault = vault;
	}

	/**
	 * Ingests an incremental token chunk, reconstitutes complete surrogates, and emits ready-to-write cleartext while
	 * holding any partial surrogate prefix in the carry buffer.
	 *
	 * @param chunk incoming text delta from SSE line
	 * @return de-anonymized text ready for downstream output
	 */
	public String processChunk(String chunk) {
		if (chunk == null || chunk.isEmpty()) {
			return "";
		}

		if (vault == null || vault.isEmpty()) {
			return chunk;
		}

		StringBuilder working = new StringBuilder(carry.length() + chunk.length());
		working.append(carry);
		working.append(chunk);
		carry.setLength(0);

		StringBuilder output = new StringBuilder(working.length());
		int i = 0;
		int len = working.length();

		while (i < len) {
			char c = working.charAt(i);
			if (c == '<') {
				// Candidate surrogate opening
				int closeIdx = working.indexOf(">", i + 1);
				if (closeIdx != -1 && (closeIdx - i) < MAX_LOOKAHEAD_K) {
					// Complete candidate surrogate found
					String surrogateCandidate = working.substring(i, closeIdx + 1);
					String plaintext = vault.resolve(surrogateCandidate);
					if (plaintext != null) {
						output.append(plaintext);
						i = closeIdx + 1;
						continue;
					}
					// Not a registered surrogate, treat as literal text
					output.append(surrogateCandidate);
					i = closeIdx + 1;
					continue;
				} else if (closeIdx == -1 && (len - i) < MAX_LOOKAHEAD_K) {
					// Incomplete surrogate prefix in flight at chunk boundary (e.g. "<PER")
					carry.append(working.substring(i));
					break;
				}
			}

			output.append(c);
			i++;
		}

		return output.toString();
	}

	/**
	 * Flushes any remaining bytes in the carry buffer upon stream completion ([DONE]).
	 *
	 * @return residual literal characters
	 */
	public String flush() {
		if (carry.length() == 0) {
			return "";
		}
		String remaining = carry.toString();
		carry.setLength(0);
		return remaining;
	}
}
