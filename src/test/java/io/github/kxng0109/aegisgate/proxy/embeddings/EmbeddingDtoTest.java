package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Embedding DTOs")
@SuppressWarnings("DataFlowIssue")
class EmbeddingDtoTest {

	@Test
	@DisplayName("EmbeddingRequest extracts text inputs from single string, list of strings, and token arrays")
	void extractTextInputsPolymorphic() {
		// Single string
		EmbeddingRequest singleString = new EmbeddingRequest("hello world", "text-embedding-3-small", null, null, null);
		assertThat(singleString.extractTextInputs()).containsExactly("hello world");

		// List of strings
		EmbeddingRequest listOfStrings = new EmbeddingRequest(
				List.of("first", "second"),
				"text-embedding-3-small",
				null,
				null,
				null
		);
		assertThat(listOfStrings.extractTextInputs()).containsExactly("first", "second");

		// Single array of token IDs: [100, 200, 300]
		EmbeddingRequest singleTokenArray = new EmbeddingRequest(
				List.of(100, 200, 300),
				"text-embedding-3-small",
				null,
				null,
				null
		);
		assertThat(singleTokenArray.extractTextInputs()).hasSize(1);

		// Array of token ID arrays: [[100, 200], [300, 400]]
		EmbeddingRequest nestedTokenArray = new EmbeddingRequest(
				List.of(List.of(100, 200), List.of(300, 400)),
				"text-embedding-3-small",
				null,
				null,
				null
		);
		assertThat(nestedTokenArray.extractTextInputs()).hasSize(2);

		// Null and empty list
		EmbeddingRequest nullInput = new EmbeddingRequest(null, "text-embedding-3-small", null, null, null);
		assertThat(nullInput.extractTextInputs()).isEmpty();

		EmbeddingRequest emptyListInput = new EmbeddingRequest(List.of(), "text-embedding-3-small", null, null, null);
		assertThat(emptyListInput.extractTextInputs()).isEmpty();

		// Other object fallback
		EmbeddingRequest otherObj = new EmbeddingRequest(12345, "text-embedding-3-small", null, null, null);
		assertThat(otherObj.extractTextInputs()).containsExactly("12345");

		// Mixed list with null and non-string elements
		java.util.List<Object> mixedList = new java.util.ArrayList<>();
		mixedList.add("firstText");
		mixedList.add(null);
		mixedList.add(new StringBuilder("secondText"));
		EmbeddingRequest mixedReq = new EmbeddingRequest(mixedList, "text-embedding-3-small", null, null, null);
		assertThat(mixedReq.extractTextInputs()).containsExactly("firstText", "secondText");
	}

	@Test
	@DisplayName("EmbeddingRequest detects Base64 encoding format request")
	void isBase64Requested() {
		EmbeddingRequest base64Req = new EmbeddingRequest("text", "model", 512, "base64", "user-1");
		assertThat(base64Req.isBase64Requested()).isTrue();
		assertThat(base64Req.dimensions()).isEqualTo(512);
		assertThat(base64Req.user()).isEqualTo("user-1");

		EmbeddingRequest floatReq = new EmbeddingRequest("text", "model", null, "float", null);
		assertThat(floatReq.isBase64Requested()).isFalse();

		EmbeddingRequest defaultReq = new EmbeddingRequest("text", "model", null, null, null);
		assertThat(defaultReq.isBase64Requested()).isFalse();
	}

	@Test
	@DisplayName("EmbeddingData creates float and base64 representations")
	void embeddingDataFactory() {
		float[] vec = new float[]{0.1f, 0.2f};
		EmbeddingData floatData = EmbeddingData.of(0, vec);
		assertThat(floatData.object()).isEqualTo("embedding");
		assertThat(floatData.index()).isZero();
		assertThat(floatData.embedding()).isEqualTo(vec);

		EmbeddingData b64Data = EmbeddingData.of(1, "b64string");
		assertThat(b64Data.index()).isEqualTo(1);
		assertThat(b64Data.embedding()).isEqualTo("b64string");
	}

	@Test
	@DisplayName("EmbeddingUsage creates usage with equal prompt and total tokens")
	void embeddingUsageFactory() {
		EmbeddingUsage usage = EmbeddingUsage.of(42);
		assertThat(usage.promptTokens()).isEqualTo(42);
		assertThat(usage.totalTokens()).isEqualTo(42);
	}

	@Test
	@DisplayName("EmbeddingResponse creates standard list envelope")
	void embeddingResponseFactory() {
		EmbeddingData data = EmbeddingData.of(0, new float[]{0.5f});
		EmbeddingResponse response = EmbeddingResponse.of("text-embedding-3-small", List.of(data), 10);

		assertThat(response.object()).isEqualTo("list");
		assertThat(response.model()).isEqualTo("text-embedding-3-small");
		assertThat(response.data()).containsExactly(data);
		assertThat(response.usage().promptTokens()).isEqualTo(10);
	}
}
