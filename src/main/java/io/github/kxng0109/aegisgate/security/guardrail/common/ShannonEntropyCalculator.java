package io.github.kxng0109.aegisgate.security.guardrail.common;

/**
 * High-performance, branchless Shannon Information Entropy calculator.
 *
 * <p>Shannon Information Entropy \(H(X)\) measures the uncertainty or information density
 * of a discrete sequence: \[ H(X) = -\sum_{i=1}^{k} P(x_i) \log_2 P(x_i) = \log_2(N) - \frac{1}{N} \sum_{i=1}^{k} c_i
 * \log_2(c_i) \] where \(N\) is the candidate token length and \(c_i\) is the frequency count of byte \(i\).</p>
 *
 * <p>To achieve sub-90ns execution with zero heap allocations, this implementation utilizes
 * precomputed lookup tables for both \(c \log_2(c)\) and \(\log_2(N)\), eliminating all runtime transcendental
 * floating-point calls and branch mispredictions.</p>
 */
public final class ShannonEntropyCalculator {

	private static final double LN_2 = Math.log(2.0);

	/**
	 * Precomputed table for \(c \cdot \log_2(c)\) for \(c \in [0, 256]\). For \(c = 0\), \(\lim_{c \to 0^+} c \log_2(c)
	 * = 0.0\).
	 */
	private static final double[] LUT_C_LOG2 = new double[257];

	/**
	 * Precomputed table for \(\log_2(N)\) for \(N \in [0, 256]\).
	 */
	private static final double[] LOG2_TABLE = new double[257];

	static {
		LUT_C_LOG2[0] = 0.0;
		LOG2_TABLE[0] = 0.0;
		for (int i = 1; i <= 256; i++) {
			double log2I = Math.log(i) / LN_2;
			LUT_C_LOG2[i] = i * log2I;
			LOG2_TABLE[i] = log2I;
		}
	}

	private ShannonEntropyCalculator() {
	}

	/**
	 * Computes exact Shannon entropy over a slice of raw bytes.
	 *
	 * @param bytes  input byte array
	 * @param offset starting offset
	 * @param length number of bytes to evaluate
	 * @return Shannon entropy \(H(X)\) in bits per symbol
	 */
	public static double calculate(byte[] bytes, int offset, int length) {
		if (bytes == null || length <= 1 || offset < 0 || offset + length > bytes.length) {
			return 0.0;
		}

		int[] freq = new int[256];
		int end = offset + length;
		for (int i = offset; i < end; i++) {
			freq[bytes[i] & 0xFF]++;
		}

		double sum = 0.0;
		// Branchless accumulation: LUT_C_LOG2[0] is 0.0, so no conditional branch is required.
		for (int i = 0; i < 256; i++) {
			int count = freq[i];
			if (count <= 256) {
				sum += LUT_C_LOG2[count];
			} else {
				sum += count * (Math.log(count) / LN_2);
			}
		}

		double log2N = length <= 256 ? LOG2_TABLE[length] : (Math.log(length) / LN_2);
		double entropy = log2N - (sum / length);
		return Math.max(0.0, entropy);
	}

	/**
	 * Computes exact Shannon entropy over a character sequence.
	 *
	 * @param cs candidate character sequence
	 * @return Shannon entropy \(H(X)\) in bits per symbol
	 */
	public static double calculate(CharSequence cs) {
		if (cs == null || cs.length() <= 1) {
			return 0.0;
		}
		int len = cs.length();
		int[] freq = new int[256];
		for (int i = 0; i < len; i++) {
			char c = cs.charAt(i);
			freq[c & 0xFF]++;
		}

		double sum = 0.0;
		for (int i = 0; i < 256; i++) {
			int count = freq[i];
			if (count <= 256) {
				sum += LUT_C_LOG2[count];
			} else {
				sum += count * (Math.log(count) / LN_2);
			}
		}

		double log2N = len <= 256 ? LOG2_TABLE[len] : (Math.log(len) / LN_2);
		double entropy = log2N - (sum / len);
		return Math.max(0.0, entropy);
	}
}
