package io.github.kxng0109.aegisgate.proxy.embeddings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VectorEncodingUtils")
@SuppressWarnings("DataFlowIssue")
class VectorEncodingUtilsTest {

	@Test
	@DisplayName("encodeToBase64 and decodeFromBase64 preserve exact float values")
	void roundTripEncoding() {
		float[] original = new float[]{0.0f, 1.0f, -0.5f, 3.14159f, Float.MAX_VALUE, Float.MIN_VALUE};
		String base64 = VectorEncodingUtils.encodeToBase64(original);

		assertThat(base64).isNotEmpty();
		float[] decoded = VectorEncodingUtils.decodeFromBase64(base64);

		assertThat(decoded).containsExactly(original);
	}

	@Test
	@DisplayName("encodeToBase64 and decodeFromBase64 handle null and empty inputs safely")
	void nullAndEmptyHandling() {
		assertThat(VectorEncodingUtils.encodeToBase64(null)).isEmpty();
		assertThat(VectorEncodingUtils.encodeToBase64(new float[0])).isEmpty();

		assertThat(VectorEncodingUtils.decodeFromBase64(null)).isEmpty();
		assertThat(VectorEncodingUtils.decodeFromBase64("")).isEmpty();
	}

	@Test
	@DisplayName("decodeFromBase64 throws IllegalArgumentException on invalid byte length")
	void invalidLengthThrows() {
		// 3 bytes instead of multiple of 4
		String invalidBase64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
		assertThatThrownBy(() -> VectorEncodingUtils.decodeFromBase64(invalidBase64))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("byte count must be a multiple of 4");
	}
}
