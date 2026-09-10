package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.IdempotencyKeys;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service coordinating embedding model resolution, batch execution, and asynchronous usage ledger recording.
 */
@Slf4j
@Service
public class EmbeddingService {

	/**
	 * Maximum input items accepted in one embedding request.
	 *
	 * <p>Retained as the documented fallback default mirrored in
	 * {@link EmbeddingProperties#DEFAULTS}; the service honors the bound properties.</p>
	 */
	public static final int MAX_BATCH_ITEMS = 2048;

	private final GatewayProperties gatewayProperties;
	private final EmbeddingAdapterResolver adapterResolver;
	private final EmbeddingBatchOrchestrator batchOrchestrator;
	private final CostCalculator costCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final EmbeddingProperties properties;

	/**
	 * Creates the service with default batching ceilings (tests).
	 */
	public EmbeddingService(
			GatewayProperties gatewayProperties,
			EmbeddingAdapterResolver adapterResolver,
			EmbeddingBatchOrchestrator batchOrchestrator,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher
	) {
		this(
				gatewayProperties, adapterResolver, batchOrchestrator, costCalculator, eventPublisher,
				EmbeddingProperties.DEFAULTS
		);
	}

	/**
	 * Creates the service with explicit batching ceilings.
	 */
	@Autowired
	public EmbeddingService(
			GatewayProperties gatewayProperties,
			EmbeddingAdapterResolver adapterResolver,
			EmbeddingBatchOrchestrator batchOrchestrator,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			EmbeddingProperties properties
	) {
		this.gatewayProperties = gatewayProperties;
		this.adapterResolver = adapterResolver;
		this.batchOrchestrator = batchOrchestrator;
		this.costCalculator = costCalculator;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
	}

	/**
	 * Processes an embedding request, managing batching, upstream routing, and ledger tracking.
	 *
	 * @param request client embedding request
	 * @param ownerId authenticated tenant/owner identifier
	 * @return OpenAI-compliant embedding response
	 */
	public EmbeddingResponse processEmbedding(EmbeddingRequest request, @Nullable String ownerId) {
		return processEmbedding(request, ownerId, null);
	}

	/**
	 * Canonical bytes for idempotency fingerprinting: model plus the input's stable string form. Logically
	 * identical requests produce identical bytes; anything else (including key reorderings) intentionally yields a
	 * distinct operation rather than risking a wrongly shared id.
	 */
	static byte[] canonicalEmbeddingBytes(EmbeddingRequest request) {
		String model = request.model() == null ? "" : request.model();
		return (model + "\n" + request.input()).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Processes an embedding request with an optional client-minted idempotency key. A valid key derives a
	 * deterministic usage-ledger id (same key plus byte-identical canonical input yields the same id on every
	 * instance), so a retried request cannot produce a duplicate ledger row; a {@code null} key preserves the
	 * previous random-id behavior (internal callers such as cache probes).
	 *
	 * @param request        client embedding request
	 * @param ownerId        authenticated tenant/owner identifier
	 * @param idempotencyKey validated client key, or {@code null}
	 * @return OpenAI-compliant embedding response
	 */
	public EmbeddingResponse processEmbedding(
			EmbeddingRequest request, @Nullable String ownerId, @Nullable String idempotencyKey) {
		validateRequest(request);

		ResolvedEmbeddingTarget target = resolveTarget(request.model());
		ProviderConfig providerConfig = target.config();
		// The effective upstream model (alias override if present, else the requested
		// name) is used end-to-end: wire payload, cost attribution, ledger event, and
		// response all name what was actually computed.
		EmbeddingRequest effectiveRequest = new EmbeddingRequest(
				request.input(),
				target.effectiveModel(),
				request.dimensions(),
				request.encodingFormat(),
				request.user()
		);
		EmbeddingAdapter adapter = adapterResolver.resolve(providerConfig.type());
		URI targetUri = resolveTargetUri(providerConfig);

		Instant start = Instant.now();
		EmbeddingResponse response;
		try {
			response = batchOrchestrator.execute(effectiveRequest, adapter, providerConfig, targetUri);
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
		long costUsdMicros = costCalculator.calculate(
				providerConfig.type(), target.effectiveModel(), promptTokens, 0);

		UUID requestId = IdempotencyKeys.resolveRequestId(
				idempotencyKey,
				ownerId == null || ownerId.isBlank() ? "" : ownerId,
				"/v1/embeddings",
				IdempotencyKeys.sha256Hex(canonicalEmbeddingBytes(request)));
		TokenUsageEvent event = new TokenUsageEvent(
				requestId,
				ownerId == null || ownerId.isBlank() ? "unknown" : ownerId,
				providerConfig.name(),
				target.effectiveModel(),
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
		if (inputs.size() > properties.maxBatchItems()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Batch size of " + inputs.size() + " exceeds maximum allowed limit of " + properties.maxBatchItems() + " items"
			);
		}
	}

	/**
	 * Resolved routing target: the provider config plus the effective upstream model id (the chain's
	 * {@code model-override} when present, else the requested name).
	 */
	private record ResolvedEmbeddingTarget(ProviderConfig config, String effectiveModel) {
	}

	private ResolvedEmbeddingTarget resolveTarget(String model) {
		ModelAlias alias = gatewayProperties.getAliases().get(model);
		if (alias != null && !alias.chain().isEmpty()) {
			ProviderRef primaryRef = alias.chain().getFirst();
			ProviderConfig config = gatewayProperties.getProviders().get(primaryRef.providerName());
			if (config != null) {
				String effectiveModel = primaryRef.modelOverride() != null
						&& !primaryRef.modelOverride().isBlank()
						? primaryRef.modelOverride()
						: model;
				return new ResolvedEmbeddingTarget(config, effectiveModel);
			}
		}

		// Direct lookup by provider key if model contains provider prefix or matches configured provider
		for (ProviderConfig config : gatewayProperties.getProviders().values()) {
			if (model.toLowerCase().contains(config.type().name().toLowerCase())) {
				return new ResolvedEmbeddingTarget(config, model);
			}
		}

		// Fallback to first available provider if configured
		if (!gatewayProperties.getProviders().isEmpty()) {
			return new ResolvedEmbeddingTarget(
					gatewayProperties.getProviders().values().iterator().next(), model);
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
