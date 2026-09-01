package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EmbeddingAdapterResolver")
class EmbeddingAdapterResolverTest {

	@Test
	@DisplayName("resolve returns matching adapter by provider type or defaults to OpenAI")
	void resolveMapping() {
		EmbeddingAdapter openAiAdapter = mock(EmbeddingAdapter.class);
		when(openAiAdapter.getProviderType()).thenReturn(ProviderType.OPENAI);

		EmbeddingAdapter ollamaAdapter = mock(EmbeddingAdapter.class);
		when(ollamaAdapter.getProviderType()).thenReturn(ProviderType.OLLAMA);

		EmbeddingAdapterResolver resolver = new EmbeddingAdapterResolver(List.of(openAiAdapter, ollamaAdapter));

		assertThat(resolver.resolve(ProviderType.OPENAI)).isSameAs(openAiAdapter);
		assertThat(resolver.resolve(ProviderType.OLLAMA)).isSameAs(ollamaAdapter);
		assertThat(resolver.resolve(ProviderType.ANTHROPIC)).isSameAs(openAiAdapter);
	}
}
