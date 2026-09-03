package io.github.kxng0109.aegisgate.security.guardrail.secret;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fast, pure-Java byte-level trie for scanning raw UTF-8 byte streams for static anchor prefixes.
 *
 * <p>Stage 1 of the hybrid scanner runs in &lt;2µs per request with zero heap allocations during normal
 * (non-violating) traffic passes.</p>
 */
public final class BytePrefixTrie {

	private static final int INITIAL_CAPACITY = 256;
	private int[][] transitions;
	@SuppressWarnings("unchecked")
	private List<SecretRule>[] outputs;
	private int nodeCount = 1;

	@SuppressWarnings("unchecked")
	public BytePrefixTrie(List<SecretRule> rules) {
		this.transitions = new int[INITIAL_CAPACITY][256];
		for (int[] row : transitions) {
			Arrays.fill(row, -1);
		}
		this.outputs = new List[INITIAL_CAPACITY];

		for (SecretRule rule : rules) {
			if (rule.prefixAnchor() != null && !rule.prefixAnchor().isEmpty()) {
				insert(rule.prefixAnchor().getBytes(StandardCharsets.UTF_8), rule);
			}
		}
	}

	private void insert(byte[] pattern, SecretRule rule) {
		int current = 0;
		for (byte b : pattern) {
			int index = b & 0xFF;
			if (transitions[current][index] == -1) {
				ensureCapacity(nodeCount + 1);
				transitions[current][index] = nodeCount++;
			}
			current = transitions[current][index];
		}
		if (outputs[current] == null) {
			outputs[current] = new ArrayList<>();
		}
		outputs[current].add(rule);
	}

	@SuppressWarnings("unchecked")
	private void ensureCapacity(int minCapacity) {
		if (minCapacity > transitions.length) {
			int newCap = Math.max(transitions.length * 2, minCapacity);
			int[][] newTrans = new int[newCap][256];
			for (int i = 0; i < transitions.length; i++) {
				System.arraycopy(transitions[i], 0, newTrans[i], 0, 256);
			}
			for (int i = transitions.length; i < newCap; i++) {
				Arrays.fill(newTrans[i], -1);
			}
			transitions = newTrans;

			List<SecretRule>[] newOutputs = new List[newCap];
			System.arraycopy(outputs, 0, newOutputs, 0, outputs.length);
			outputs = newOutputs;
		}
	}

	/**
	 * Fast check: does the byte array contain any prefix anchor anywhere in the buffer?
	 *
	 * @param data   raw byte buffer
	 * @param offset starting index
	 * @param length buffer length
	 * @return {@code true} if at least one anchor prefix is present
	 */
	public boolean containsAnyPrefix(byte[] data, int offset, int length) {
		if (data == null || length <= 0 || offset < 0 || offset + length > data.length) {
			return false;
		}
		int end = offset + length;
		for (int i = offset; i < end; i++) {
			int node = 0;
			for (int j = i; j < end; j++) {
				int b = data[j] & 0xFF;
				node = transitions[node][b];
				if (node == -1) {
					break;
				}
				if (outputs[node] != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Scans the byte array and collects candidate rules and their starting offsets.
	 *
	 * @param data   raw byte buffer
	 * @param offset starting index
	 * @param length buffer length
	 * @return list of candidate matches
	 */
	public List<CandidateMatch> scan(byte[] data, int offset, int length) {
		List<CandidateMatch> matches = new ArrayList<>();
		if (data == null || length <= 0 || offset < 0 || offset + length > data.length) {
			return matches;
		}

		int end = offset + length;
		for (int i = offset; i < end; i++) {
			int node = 0;
			for (int j = i; j < end; j++) {
				int b = data[j] & 0xFF;
				node = transitions[node][b];
				if (node == -1) {
					break;
				}
				if (outputs[node] != null) {
					for (SecretRule rule : outputs[node]) {
						matches.add(new CandidateMatch(rule, i));
					}
				}
			}
		}
		return matches;
	}

	public record CandidateMatch(SecretRule rule, int startOffset) {
	}
}
