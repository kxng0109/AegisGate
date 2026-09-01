package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the appropriate {@link EmbeddingAdapter} for a given {@link ProviderType}.
 */
@Component
public class EmbeddingAdapterResolver {

	private final Map<ProviderType, EmbeddingAdapter> adapters = new EnumMap<>(ProviderType.class);

	public EmbeddingAdapterResolver(List<EmbeddingAdapter> adapterList) {
		for (EmbeddingAdapter adapter : adapterList) {
			adapters.put(adapter.getProviderType(), adapter);
		}
	}

	/**
	 * Resolves the adapter for the given provider type, defaulting to the OpenAI adapter for passthrough.
	 *
	 * @param providerType target provider dialect
	 * @return matching adapter
	 */
	public EmbeddingAdapter resolve(ProviderType providerType) {
		EmbeddingAdapter adapter = adapters.get(providerType);
		if (adapter != null) {
			return adapter;
		}
		return adapters.get(ProviderType.OPENAI);
	}
}
