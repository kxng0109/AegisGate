package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service coordinating embedding model resolution, batch execution, and asynchronous usage ledger recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

	private static final int MAX_BATCH_ITEMS = 2048;

	private final GatewayProperties gatewayProperties;
	private final EmbeddingAdapterResolver adapterResolver;
	private final EmbeddingBatchOrchestrator batchOrchestrator;
	private final CostCalculator costCalculator;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * Processes an embedding request, managing batching, upstream routing, and ledger tracking.
	 *
	 * @param request client embedding request
	 * @param ownerId authenticated tenant/owner identifier
	 * @return OpenAI-compliant embedding response
	 */
	public EmbeddingResponse processEmbedding(EmbeddingRequest request, @Nullable String ownerId) {
		validateRequest(request);

		ProviderConfig providerConfig = resolveProvider(request.model());
		EmbeddingAdapter adapter = adapterResolver.resolve(providerConfig.type());
		URI targetUri = resolveTargetUri(providerConfig);

		Instant start = Instant.now();
		EmbeddingResponse response;
		try {
			response = batchOrchestrator.execute(request, adapter, providerConfig, targetUri);
		} catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Embedding upstream call failed: {}", ex.getMessage());
			throw new ResponseStatusException(
					HttpStatus.BAD_GATEWAY,
					"Embedding upstream provider error: " + ex.getMessage(),
					ex
			);
		}

		long durationMs = Duration.between(start, Instant.now()).toMillis();
		int promptTokens = response.usage() != null ? response.usage().promptTokens() : 0;
		long costUsdMicros = costCalculator.calculate(providerConfig.type(), request.model(), promptTokens, 0);

		UUID requestId = UUID.randomUUID();
		TokenUsageEvent event = new TokenUsageEvent(
				requestId,
				ownerId == null || ownerId.isBlank() ? "unknown" : ownerId,
				providerConfig.name(),
				request.model(),
				promptTokens,
				0,
				promptTokens,
				durationMs,
				costUsdMicros,
				Instant.now()
		);
		eventPublisher.publishEvent(event);

		return response;
	}

	private void validateRequest(EmbeddingRequest request) {
		if (request.model() == null || request.model().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'model' is required");
		}
		List<String> inputs = request.extractTextInputs();
		if (inputs.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'input' cannot be empty");
		}
		if (inputs.size() > MAX_BATCH_ITEMS) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Batch size of " + inputs.size() + " exceeds maximum allowed limit of " + MAX_BATCH_ITEMS + " items"
			);
		}
	}

	private ProviderConfig resolveProvider(String model) {
		ModelAlias alias = gatewayProperties.getAliases().get(model);
		if (alias != null && !alias.chain().isEmpty()) {
			ProviderRef primaryRef = alias.chain().getFirst();
			ProviderConfig config = gatewayProperties.getProviders().get(primaryRef.providerName());
			if (config != null) {
				return config;
			}
		}

		// Direct lookup by provider key if model contains provider prefix or matches configured provider
		for (ProviderConfig config : gatewayProperties.getProviders().values()) {
			if (model.toLowerCase().contains(config.type().name().toLowerCase())) {
				return config;
			}
		}

		// Fallback to first available provider if configured
		if (!gatewayProperties.getProviders().isEmpty()) {
			return gatewayProperties.getProviders().values().iterator().next();
		}

		throw new ResponseStatusException(
				HttpStatus.NOT_FOUND,
				"No upstream provider configured for embedding model: " + model
		);
	}

	private URI resolveTargetUri(ProviderConfig providerConfig) {
		URI base = providerConfig.baseUrl();
		String defaultPath = switch (providerConfig.type()) {
			case OLLAMA -> "/api/embed";
			case OPENAI, ANTHROPIC, DEEPSEEK, GEMINI, VERTEX_AI -> "/v1/embeddings";
		};

		return resolveEndpoint(base, defaultPath);
	}

	public static URI resolveEndpoint(URI baseUrl, String defaultPath) {
		String baseStr = baseUrl.toString();
		if (baseStr.endsWith("/")) {
			baseStr = baseStr.substring(0, baseStr.length() - 1);
		}
		String path = defaultPath.startsWith("/") ? defaultPath : "/" + defaultPath;
		if (baseStr.endsWith("/v1") && path.startsWith("/v1/")) {
			path = path.substring(3);
		}
		if (baseStr.endsWith("/v2") && path.startsWith("/v2/")) {
			path = path.substring(3);
		}
		return URI.create(baseStr + path);
	}
}
