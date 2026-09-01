package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates batch partitioning, concurrent sub-batch execution across virtual threads, and deterministic index
 * reassembly for embedding workloads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingBatchOrchestrator {

	private static final int MAX_CONCURRENT_SUB_REQUESTS = 4;
	private final HttpClient httpClient;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * Executes an embedding request with transparent batch chunking and reassembly.
	 *
	 * @param request        original client embedding request
	 * @param adapter        upstream protocol adapter
	 * @param providerConfig upstream provider configuration
	 * @param targetUri      resolved target endpoint URI
	 * @return OpenAI-compliant embedding response with exact 0..N-1 indexing
	 */
	public EmbeddingResponse execute(
			EmbeddingRequest request,
			EmbeddingAdapter adapter,
			ProviderConfig providerConfig,
			URI targetUri
	) throws IOException, InterruptedException {
		List<String> allInputs = request.extractTextInputs();
		if (allInputs.isEmpty()) {
			return EmbeddingResponse.of(request.model(), List.of(), 0);
		}

		int maxBatchSize = Math.max(1, adapter.getMaxBatchSize());
		int totalInputs = allInputs.size();

		if (totalInputs <= maxBatchSize) {
			return executeSingleBatch(request, allInputs, 0, adapter, providerConfig, targetUri);
		}

		return executeMultiBatch(request, allInputs, maxBatchSize, adapter, providerConfig, targetUri);
	}

	private EmbeddingResponse executeSingleBatch(
			EmbeddingRequest request,
			List<String> textBatch,
			int baseIndex,
			EmbeddingAdapter adapter,
			ProviderConfig providerConfig,
			URI targetUri
	) throws IOException, InterruptedException {
		HttpRequest httpRequest = adapter.buildRequest(request, textBatch, providerConfig, targetUri);
		HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

		if (httpResponse.statusCode() >= 400) {
			throw new IOException("Upstream embedding provider returned HTTP " + httpResponse.statusCode() + ": "
					                      + new String(httpResponse.body()));
		}

		NormalizedEmbeddingResult result = adapter.parseResponse(httpResponse.body(), request, request.model());
		List<EmbeddingData> dataList = new ArrayList<>(result.vectors().size());

		for (int j = 0; j < result.vectors().size(); j++) {
			int globalIndex = baseIndex + j;
			if (request.isBase64Requested()) {
				String b64 = (result.base64Vectors() != null && j < result.base64Vectors().size())
						? result.base64Vectors().get(j)
						: VectorEncodingUtils.encodeToBase64(result.vectors().get(j));
				dataList.add(EmbeddingData.of(globalIndex, b64));
			} else {
				dataList.add(EmbeddingData.of(globalIndex, result.vectors().get(j)));
			}
		}

		return EmbeddingResponse.of(request.model(), dataList, result.promptTokens());
	}

	private EmbeddingResponse executeMultiBatch(
			EmbeddingRequest request,
			List<String> allInputs,
			int maxBatchSize,
			EmbeddingAdapter adapter,
			ProviderConfig providerConfig,
			URI targetUri
	) throws IOException, InterruptedException {
		int totalInputs = allInputs.size();
		int numChunks = (int) Math.ceil((double) totalInputs / maxBatchSize);

		EmbeddingData[] assembled = new EmbeddingData[totalInputs];
		AtomicInteger totalPromptTokens = new AtomicInteger(0);
		Semaphore semaphore = new Semaphore(MAX_CONCURRENT_SUB_REQUESTS);

		List<CompletableFuture<Void>> futures = new ArrayList<>(numChunks);

		for (int m = 0; m < numChunks; m++) {
			int fromIndex = m * maxBatchSize;
			int toIndex = Math.min(fromIndex + maxBatchSize, totalInputs);
			List<String> subBatch = allInputs.subList(fromIndex, toIndex);
			int baseIndex = fromIndex;

			CompletableFuture<Void> future = CompletableFuture.runAsync(
					() -> {
						try {
							semaphore.acquire();
							try {
								EmbeddingResponse subResponse = executeSingleBatch(
										request, subBatch, baseIndex, adapter, providerConfig, targetUri
								);
								for (EmbeddingData item : subResponse.data()) {
									assembled[item.index()] = item;
								}
								totalPromptTokens.addAndGet(subResponse.usage().promptTokens());
							} finally {
								semaphore.release();
							}
						} catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Embedding sub-batch interrupted", ex);
						} catch (Exception ex) {
							throw new RuntimeException("Embedding sub-batch execution failed: " + ex.getMessage(), ex);
						}
					}, executor
			);

			futures.add(future);
		}

		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} catch (Exception ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof IOException ioEx) {
				throw ioEx;
			}
			if (cause instanceof InterruptedException intEx) {
				throw intEx;
			}
			throw new IOException("Failed executing multi-batch embedding request: " + cause.getMessage(), cause);
		}

		return EmbeddingResponse.of(request.model(), Arrays.asList(assembled), totalPromptTokens.get());
	}
}
