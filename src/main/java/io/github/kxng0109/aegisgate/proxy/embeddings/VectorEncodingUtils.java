package io.github.kxng0109.aegisgate.proxy.embeddings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Utility for encoding and decoding high-dimensional dense vector embeddings between IEEE 754 32-bit single-precision
 * float arrays and Little-Endian Base64 binary strings matching the OpenAI embeddings specification.
 */
public final class VectorEncodingUtils {

	private VectorEncodingUtils() {
	}

	/**
	 * Encodes a primitive {@code float[]} vector into a Little-Endian RFC 4648 Base64 string.
	 *
	 * @param vector dense float vector
	 * @return Base64-encoded string representing 4 bytes per float dimension
	 */
	public static String encodeToBase64(float[] vector) {
		if (vector == null) {
			return "";
		}
		byte[] bytes = new byte[vector.length * 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(vector);
		return Base64.getEncoder().encodeToString(bytes);
	}

	/**
	 * Decodes a Little-Endian Base64 string back into a primitive {@code float[]} vector.
	 *
	 * @param base64 Base64-encoded string
	 * @return primitive float array
	 * @throws IllegalArgumentException if the decoded byte array length is not a multiple of 4
	 */
	public static float[] decodeFromBase64(String base64) {
		if (base64 == null || base64.isEmpty()) {
			return new float[0];
		}
		byte[] bytes = Base64.getDecoder().decode(base64);
		if (bytes.length % 4 != 0) {
			throw new IllegalArgumentException("Invalid Base64 embedding length: byte count must be a multiple of 4");
		}
		float[] vector = new float[bytes.length / 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
		return vector;
	}
}
