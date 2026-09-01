package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * Protocol adapter interface for embedding model providers.
 */
public interface EmbeddingAdapter {

	/**
	 * Returns the upstream provider type supported by this adapter.
	 */
	ProviderType getProviderType();

	/**
	 * Returns the maximum input texts batch size supported in a single upstream HTTP call.
	 */
	int getMaxBatchSize();

	/**
	 * Builds an outbound {@link HttpRequest} for the given text batch.
	 *
	 * @param request        original client embedding request
	 * @param textBatch      partitioned subset of input text items
	 * @param providerConfig upstream provider credentials and configuration
	 * @param targetUri      resolved target endpoint URI
	 * @return HTTP request ready for execution
	 */
	HttpRequest buildRequest(
			EmbeddingRequest request,
			List<String> textBatch,
			ProviderConfig providerConfig,
			URI targetUri
	);

	/**
	 * Parses and normalizes the upstream response body into a uniform {@link NormalizedEmbeddingResult}.
	 *
	 * @param responseBody    raw response byte array
	 * @param originalRequest original client request
	 * @param modelName       upstream model identifier
	 * @return normalized embedding result
	 */
	NormalizedEmbeddingResult parseResponse(
			byte[] responseBody,
			EmbeddingRequest originalRequest,
			String modelName
	);
}
