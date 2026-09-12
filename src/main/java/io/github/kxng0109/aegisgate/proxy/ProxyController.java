package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.budget.BudgetDecision;
import io.github.kxng0109.aegisgate.budget.BudgetEnforcer;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheLookupResult;
import io.github.kxng0109.aegisgate.cache.contracts.CacheStatus;
import io.github.kxng0109.aegisgate.cache.engine.AegisCacheService;
import io.github.kxng0109.aegisgate.cache.engine.streaming.CachedStreamReconstitution;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import io.github.kxng0109.aegisgate.proxy.protocol.*;
import io.github.kxng0109.aegisgate.proxy.sse.LineTooLongException;
import io.github.kxng0109.aegisgate.proxy.sse.SseConnectionLimitException;
import io.github.kxng0109.aegisgate.proxy.sse.SseFlushStrategy;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory;
import io.github.kxng0109.aegisgate.security.compliance.MerkleAuditLedger;
import io.github.kxng0109.aegisgate.security.compliance.ZeroDataRetentionEnforcer;
import io.github.kxng0109.aegisgate.security.filter.IngressSecurityFilter;
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
import io.github.kxng0109.aegisgate.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.aegisgate.security.guardrail.injection.SystemPromptProtectionEngine;
import io.github.kxng0109.aegisgate.security.guardrail.pii.EphemeralPiiVault;
import io.github.kxng0109.aegisgate.security.guardrail.streaming.MidStreamKillSwitch;
import io.github.kxng0109.aegisgate.security.guardrail.streaming.SlidingWindowAhoCorasick;
import io.github.kxng0109.aegisgate.security.guardrail.streaming.StreamingJsonPdaValidator;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * REST controller exposing the chat completions proxy endpoint.
 *
 * <p>The client always talks to this one OpenAI shaped endpoint. Behind it the
 * controller resolves the requested model to a {@link ModelAlias}, asks the {@link FailoverOrchestrator} to pick a
 * winning provider, and relays that provider's stream back through its {@link ProtocolAdapter} normalizer, so the
 * client sees OpenAI shaped SSE no matter which dialect the winner spoke. Failover has already happened by the time
 * streaming begins, so the client never sees a switch.</p>
 *
 * <p>When a stream completes with token usage, a single {@link TokenUsageEvent}
 * is published for the asynchronous ledger. Publishing happens after the last byte was written, never inside the
 * streaming loop, and the listener runs on its own executor, so accounting can never slow the response.</p>
 */
@Slf4j
@RestController
@Tag(name = "Proxy - Chat Completions", description = "OpenAI-compatible chat completions proxy with rate-limiting, failover, and multi-tier caching")
public class ProxyController {

	private final FailoverOrchestrator failoverOrchestrator;
	private final GatewayProperties gatewayProperties;
	private final ObjectMapper objectMapper;
	private final ProtocolAdapterResolver adapterResolver;
	private final CostCalculator costCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final SseFlushStrategy flushStrategy;
	private final SseLineGuardFactory lineGuardFactory;
	private final @Nullable AegisCacheService cacheService;
	private final @Nullable CachedStreamReconstitution cachedStreamReconstitution;
	private final @Nullable MerkleAuditLedger auditLedger;
	private final @Nullable SystemPromptProtectionEngine systemPromptProtectionEngine;
	private final @Nullable GuardrailProperties guardrailProperties;
	private final @Nullable ZeroDataRetentionEnforcer zdrEnforcer;

	private volatile @Nullable BudgetEnforcer budgetEnforcer;

	/**
	 * Full enterprise constructor injecting all components including cache, security, and compliance subsystems.
	 */
	@Autowired
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory,
			@Nullable AegisCacheService cacheService,
			@Nullable CachedStreamReconstitution cachedStreamReconstitution,
			@Nullable MerkleAuditLedger auditLedger,
			@Nullable SystemPromptProtectionEngine systemPromptProtectionEngine,
			@Nullable GuardrailProperties guardrailProperties,
			@Nullable ZeroDataRetentionEnforcer zdrEnforcer
	) {
		this.failoverOrchestrator = failoverOrchestrator;
		this.gatewayProperties = gatewayProperties;
		this.objectMapper = objectMapper;
		this.adapterResolver = adapterResolver;
		this.costCalculator = costCalculator;
		this.eventPublisher = eventPublisher;
		this.flushStrategy = flushStrategy;
		this.lineGuardFactory = lineGuardFactory;
		this.cacheService = cacheService;
		this.cachedStreamReconstitution = cachedStreamReconstitution;
		this.auditLedger = auditLedger;
		this.systemPromptProtectionEngine = systemPromptProtectionEngine;
		this.guardrailProperties = guardrailProperties;
		this.zdrEnforcer = zdrEnforcer;
	}

	/**
	 * Constructor for tests and contexts with cache subsystem.
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory,
			@Nullable AegisCacheService cacheService,
			@Nullable CachedStreamReconstitution cachedStreamReconstitution
	) {
		this(
				failoverOrchestrator,
				gatewayProperties,
				objectMapper,
				adapterResolver,
				costCalculator,
				eventPublisher,
				flushStrategy,
				lineGuardFactory,
				cacheService,
				cachedStreamReconstitution,
				null,
				null,
				null,
				null
		);
	}

	/**
	 * Convenience constructor for existing tests without cache or guardrail subsystems.
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory
	) {
		this(
				failoverOrchestrator,
				gatewayProperties,
				objectMapper,
				adapterResolver,
				costCalculator,
				eventPublisher,
				flushStrategy,
				lineGuardFactory,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	/**
	 * Proxies an OpenAI shaped chat completion request to the configured provider chain and streams the normalized SSE
	 * response back.
	 *
	 * @param rawBody the raw request body
	 * @param request the servlet request, used to read the authenticated owner
	 * @return a streaming response, or a JSON error for 400, 404, 502, 503, 504
	 */
	@Operation(
			summary = "Relay OpenAI-compatible chat completion",
			description = """
					Proxies chat completion requests to the configured provider failover chain (OpenAI, Anthropic, Ollama, OpenRouter).
					Automatically resolves L0 in-memory and L1/L2 Redis semantic cache entries before routing to upstream providers.
					Streams normalized Server-Sent Events (SSE) back to the client.
					""",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "SSE stream or cached completion relayed successfully",
					headers = {
							@Header(name = "X-Cache", description = "Cache tier status: HIT (L0-Memory), HIT (L1-Exact), HIT (L2-Semantic), or omitted on MISS", schema = @Schema(type = "string", example = "HIT (L2-Semantic)")),
							@Header(name = "X-Aegis-Similarity-Score", description = "Cosine similarity score for L2 semantic hits", schema = @Schema(type = "string", example = "0.9650")),
							@Header(name = "Age", description = "Age of the cached response in seconds", schema = @Schema(type = "string", example = "42")),
							@Header(name = "X-RateLimit-Remaining-RPM", description = "Remaining requests allowed in the current minute window", schema = @Schema(type = "integer", example = "118")),
							@Header(name = "X-RateLimit-Remaining-TPM", description = "Remaining token budget in the current minute window", schema = @Schema(type = "integer", example = "485000"))
					},
					content = @Content(
							mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
							examples = @ExampleObject(
									name = "Streaming SSE Chunk Sequence",
									summary = "Standard OpenAI Server-Sent Event stream",
									value = """
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
											
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{"content":"Hello! How can I assist you today?"},"finish_reason":null}]}
											
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
											
											data: [DONE]
											"""
							)
					)
			),
			@ApiResponse(
					responseCode = "400",
					description = "Malformed JSON request, missing model parameter, or empty payload",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"model is required\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "401",
					description = "Missing, invalid, or expired Virtual API Key (Authorization: Bearer gw-...)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"invalid API key\",\"type\":\"authentication_error\"}}"))
			),
			@ApiResponse(
					responseCode = "403",
					description = "Virtual API Key is disabled or unauthorized for the requested model alias",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"key disabled or model not allowed\",\"type\":\"permission_error\"}}"))
			),
			@ApiResponse(
					responseCode = "404",
					description = "Requested model alias is not registered in gateway routing configuration",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"unknown model: gpt-unknown\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "413",
					description = "Request body exceeds configured size limit (64 KB cap)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"request body exceeds limit\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "429",
					description = "Virtual API Key exceeded RPM or TPM rate limit quota",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":\"rate_limit_error\"}}"))
			),
			@ApiResponse(
					responseCode = "502",
					description = "All upstream model providers in failover chain returned errors",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"all providers failed\",\"type\":\"upstream_error\"}}"))
			),
			@ApiResponse(
					responseCode = "503",
					description = "Redis, database, or all upstream provider circuits are unavailable/tripped",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"service temporarily unavailable\",\"type\":\"upstream_error\"}}"))
			),
			@ApiResponse(
					responseCode = "504",
					description = "Upstream provider failover chain timed out",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"gateway timeout\",\"type\":\"timeout_error\"}}"))
			)
	})
	@PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> proxyChatCompletions(
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "OpenAI-compatible chat completion payload specifying model, messages, temperature, and stream options",
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OpenAiChatRequest.class),
							examples = {
									@ExampleObject(
											name = "Standard Streaming Request",
											summary = "Standard multi-turn chat request with streaming",
											value = """
													{
													  "model": "gpt-56-luna",
													  "messages": [
													    {"role": "system", "content": "You are a concise, helpful technical assistant."},
													    {"role": "user", "content": "Explain zero-copy vector serialization in two sentences."}
													  ],
													  "temperature": 0.0,
													  "stream": true,
													  "stream_options": {"include_usage": true}
													}
													"""
									),
									@ExampleObject(
											name = "Non-Streaming Request",
											summary = "Direct completion request",
											value = """
													{
													  "model": "claude-sonnet-4-5",
													  "messages": [
													    {"role": "user", "content": "Hello world"}
													  ],
													  "temperature": 0.0,
													  "stream": false
													}
													"""
									)
							}
					)
			)
			@RequestBody String rawBody,
			HttpServletRequest request
	) {
		String trimmed = rawBody == null ? "" : rawBody.trim();
		if (trimmed.isEmpty()) {
			return errorResponse(HttpStatus.BAD_REQUEST, "empty request body");
		}

		String model = extractModel(trimmed);
		if (model == null || model.isBlank()) {
			return errorResponse(HttpStatus.BAD_REQUEST, "model is required");
		}

		ModelAlias alias = gatewayProperties.getAliases().get(model);
		if (alias == null) {
			return errorResponse(HttpStatus.NOT_FOUND, "unknown model: " + model);
		}

		final String idempotencyKey;
		try {
			idempotencyKey = IdempotencyKeys.validateOrNull(request.getHeader(IdempotencyKeys.HEADER));
		} catch (IllegalArgumentException malformed) {
			return errorResponse(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key");
		}

		@Nullable String ownerId = (String) request.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE);
		OpenAiChatRequest chatRequest = parseChatRequest(trimmed);

		if (cacheService != null && cachedStreamReconstitution != null && chatRequest != null) {
			CacheLookupResult cacheResult = cacheService.evaluateCache(chatRequest, request, ownerId);
			if (cacheResult.isHit() && cacheResult.entry() != null) {
				CacheEntry entry = cacheResult.entry();
				boolean clientWantsUsage = requestsUsage(trimmed);
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(MediaType.TEXT_EVENT_STREAM);
				headers.setCacheControl("no-cache");
				headers.set("X-Accel-Buffering", "no");
				headers.set(
						"X-Cache",
						cacheResult.status() == CacheStatus.HIT_L0 ? "HIT (L0-Memory)" : (
								cacheResult.status() == CacheStatus.HIT_L1 ? "HIT (L1-Exact)" : "HIT (L2-Semantic)")
				);
				headers.set(
						"X-Aegis-Similarity-Score",
						String.format(Locale.ROOT, "%.4f", cacheResult.similarityScore())
				);
				if (entry.createdAt() != null) {
					headers.set("Age", String.valueOf(Duration.between(entry.createdAt(), Instant.now()).toSeconds()));
				}
				return ResponseEntity.ok().headers(headers).body(out -> cachedStreamReconstitution.streamCachedResponse(
						entry,
						model,
						clientWantsUsage,
						out
				));
			}
		}

		// Spend-budget gate: cache misses only (hits served ~free and bypass spend).
		// Runs after alias/idempotency validation, before any upstream spend.
		ResponseEntity<StreamingResponseBody> budgetDenied = checkBudgetOrNull(
				alias, model, ownerId, trimmed,
				(String) request.getAttribute(KeyAuthFilter.KEY_HASH_ATTRIBUTE), idempotencyKey);
		if (budgetDenied != null) {
			return budgetDenied;
		}

		ProviderResponse providerResponse;
		try {			providerResponse = failoverOrchestrator.execute(alias, trimmed).join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof UpstreamUnavailableException upstream) {
				throw upstream;
			}
			log.warn("Upstream request failed unexpectedly: {}", cause == null ? "unknown cause" : cause.getMessage());
			throw new UpstreamUnavailableException(
					"upstream request failed unexpectedly",
					cause, false, false
			);
		}

		int status = providerResponse.response().statusCode();
		if (status != HttpStatus.OK.value()) {
			return ResponseEntity.status(status)
			                     .contentType(MediaType.APPLICATION_JSON)
			                     .body(out -> relayRaw(providerResponse, out));
		}

		ProviderConfig config = gatewayProperties.getProviders().get(providerResponse.providerName());
		ProviderType providerType = config == null ? ProviderType.OPENAI : config.type();
		ProtocolAdapter adapter = adapterResolver.resolve(providerType);
		boolean clientWantsUsage = requestsUsage(trimmed);
		UUID requestId = IdempotencyKeys.resolveRequestId(
				idempotencyKey,
				ownerId == null ? "" : ownerId,
				request.getRequestURI(),
				IdempotencyKeys.sha256Hex(trimmed.getBytes(StandardCharsets.UTF_8)));

		HttpHeaders headers = new HttpHeaders();
		headers.setCacheControl("no-cache");
		headers.set("X-Accel-Buffering", "no");

		if (auditLedger != null) {
			MerkleAuditLedger.AuditReceipt receipt = auditLedger.recordTransaction(
					ownerId, String.valueOf(requestId), trimmed.getBytes(StandardCharsets.UTF_8), null
			);
			headers.set("X-Aegis-Audit-Receipt", receipt.receiptHeaderValue());
		}
		if (zdrEnforcer != null) {
			zdrEnforcer.applyHeaders(headers);
		}

		String upstreamContentType = providerResponse.response().headers() == null ? ""
				: providerResponse.response().headers().firstValue("Content-Type").orElse("");
		// Absent headers (or an unrecognized type) keep the historical SSE path; only a
		// positively non-streaming content type diverts to the JSON completion relay.
		boolean upstreamStreaming = upstreamContentType.isBlank()
				|| upstreamContentType.toLowerCase(Locale.ROOT).contains("text/event-stream")
				|| upstreamContentType.toLowerCase(Locale.ROOT).contains("application/x-ndjson");
		if (!upstreamStreaming) {
			headers.setContentType(MediaType.APPLICATION_JSON);
			return ResponseEntity.ok().headers(headers).body(out -> relayJson(
					providerResponse, requestId, ownerId, providerType,
					providerResponse.providerName(), model, chatRequest, request, out
			));
		}

		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		return ResponseEntity.ok().headers(headers).body(out -> relaySse(
				providerResponse, adapter.newNormalizer(clientWantsUsage, model), out,
				requestId, ownerId, providerType, providerResponse.providerName(), model,
				chatRequest, request
		));
	}

	private void relaySse(
			ProviderResponse providerResponse,
			SseNormalizer normalizer,
			OutputStream out,
			UUID requestId,
			@Nullable String ownerId,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable OpenAiChatRequest chatRequest,
			HttpServletRequest servletRequest
	) throws IOException {
		long startedNanos = System.nanoTime();
		ServletOutputStream servletOut = out instanceof ServletOutputStream candidate ? candidate : null;
		SseFlushStrategy.FlushHandle flushHandle = null;
		if (servletOut != null) {
			try {
				flushHandle = flushStrategy.register(servletOut);
			} catch (SseConnectionLimitException ex) {
				log.warn("SSE stream rejected, connection limit reached: {}", ex.getMessage());
				return;
			}
		}

		// Create per-stream line guard
		SseLineGuard lineGuard = lineGuardFactory.newGuard(
				SseLineGuard.ProviderType.from(providerType),
				providerName,
				UUID.randomUUID()
		);

		EphemeralPiiVault vault = (EphemeralPiiVault) servletRequest.getAttribute(IngressSecurityFilter.PII_VAULT_ATTRIBUTE);
		SlidingWindowAhoCorasick deAnonymizer = vault != null ? new SlidingWindowAhoCorasick(vault) : null;
		StreamingJsonPdaValidator jsonPda = (guardrailProperties != null
				&& guardrailProperties.isStreamingValidationEnabled())
				? new StreamingJsonPdaValidator() : null;

		SystemPromptProtectionEngine.StreamingShingleTracker shingleTracker = null;
		if (systemPromptProtectionEngine != null && chatRequest != null && chatRequest.messages() != null) {
			for (OpenAiChatRequest.Message msg : chatRequest.messages()) {
				if ("system".equalsIgnoreCase(msg.role()) && msg.content() != null && msg.content().isString()) {
					var hashes = systemPromptProtectionEngine.computeShingleHashes(msg.content().asString());
					if (!hashes.isEmpty()) {
						shingleTracker = systemPromptProtectionEngine.newTracker(hashes);
						break;
					}
				}
			}
		}

		StringBuilder accumulatedContent = new StringBuilder();
		try {
			try (var lines = providerResponse.response().body()) {
				for (String line : (Iterable<String>) lines::iterator) {
					// Guard the raw upstream line before normalization
					List<String> guarded = lineGuard.checkLine(
							line,
							SseLineGuard.ProviderType.from(providerType)
					);

					if (lineGuard.isRejected()) {
						// Write SSE error event and close
						for (String s : guarded) {
							writeSse(out, s);
						}
						out.flush();
						lineGuard.onStreamAbort("line_too_long");
						return;
					}
					if (guarded.isEmpty()) {
						continue; // line dropped (REJECT_LINE_CONTINUE)
					}

					List<String> normalized = normalizer.normalizeLine(line);
					for (String toWrite : normalized) {
						String delta = extractDelta(toWrite);
						// Tracks whether toWrite was rewritten below: a successful rewrite sets
						// the delta content verbatim, so re-parsing the rewritten line (a second
						// full JSON parse per chunk) is skipped in favor of the known content.
						// Reference comparison is exact here: replaceDeltaContent returns the
						// identical String reference only when it made no change.
						boolean deltaRebased = false;
						if (delta != null && !delta.isEmpty()) {
							if (shingleTracker != null && shingleTracker.ingestChunk(delta)) {
								MidStreamKillSwitch.terminate(out, lines, "system_prompt_exfiltration");
								lineGuard.onStreamAbort("system_prompt_exfiltration");
								return;
							}
							if (jsonPda != null) {
								jsonPda.ingest(delta);
							}
							if (deAnonymizer != null) {
								String deAnonymized = deAnonymizer.processChunk(delta);
								if (!deAnonymized.equals(delta)) {
									String rewritten = replaceDeltaContent(toWrite, deAnonymized);
									if (rewritten != toWrite) {
										toWrite = rewritten;
										delta = deAnonymized;
										deltaRebased = true;
									}
								}
							}
						}

						if (deltaRebased) {
							if (delta != null) {
								accumulatedContent.append(delta);
							}
						} else {
							extractDeltaContent(toWrite, accumulatedContent);
						}
						byte[] bytes = toWrite.getBytes(StandardCharsets.UTF_8);
						out.write(bytes);
						out.write('\n');
						if (flushHandle != null && servletOut != null) {
							if (flushStrategy.onWrite(servletOut, bytes.length + 1)) {
								return;
							}
						} else {
							out.flush();
						}
					}
					if (normalizer.isDone()) {
						if (deAnonymizer != null) {
							String leftover = deAnonymizer.flush();
							if (!leftover.isEmpty()) {
								accumulatedContent.append(leftover);
							}
						}
						break;
					}
				}
			}
		} catch (LineTooLongException ex) {
			// Body handler detected oversized line during byte decoding
			writeSseError(out, ex.limitBytes(), ex.actualBytes(), ex.provider());
			lineGuard.onStreamAbort("line_too_long");
			return;
		} catch (IOException ex) {
			// Downstream client disconnected
			return;
		} finally {
			if (vault != null) {
				vault.close();
			}
			if (flushHandle != null) {
				flushStrategy.unregister(flushHandle);
			}
		}

		SseNormalizer.UsageInfo usage = normalizer.usage();
		if (usage != null) {
			long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
			String model = normalizer.upstreamModel() == null ? requestedModel : normalizer.upstreamModel();

			long cacheRead = 0L;
			long cacheWrite = 0L;
			long reasoning = 0L;
			if (normalizer instanceof AnthropicSseNormalizer anthropicNormalizer) {
				cacheRead = anthropicNormalizer.cacheReadInputTokens();
				cacheWrite = anthropicNormalizer.cacheCreationInputTokens();
				reasoning = anthropicNormalizer.reasoningTokens();
			} else if (normalizer instanceof DeepSeekSseNormalizer deepSeekNormalizer) {
				cacheRead = deepSeekNormalizer.cachedTokens() != null ? deepSeekNormalizer.cachedTokens() : 0L;
				reasoning = deepSeekNormalizer.reasoningTokens() != null ? deepSeekNormalizer.reasoningTokens() : 0L;
			}
			long uncachedPrompt = Math.max(0L, usage.promptTokens() - cacheRead);

			long costUsdMicros = (cacheRead > 0 || cacheWrite > 0)
					? costCalculator.calculate(
					providerType, model,
					usage.promptTokens(), usage.completionTokens(),
					uncachedPrompt, cacheRead, cacheWrite
			)
					: costCalculator.calculate(
					providerType, model,
					usage.promptTokens(), usage.completionTokens()
			);
			eventPublisher.publishEvent(new TokenUsageEvent(
					requestId, ownerId, providerName, model,
					usage.promptTokens(), usage.completionTokens(),
					usage.promptTokens() + usage.completionTokens(),
					durationMs, costUsdMicros, Instant.now(),
					uncachedPrompt, cacheRead, cacheWrite, reasoning,
					costUsdMicros, costUsdMicros, null
			));

			if (cacheService != null && chatRequest != null) {
				int pt = (int) Math.min(Integer.MAX_VALUE, usage.promptTokens());
				int ct = (int) Math.min(Integer.MAX_VALUE, usage.completionTokens());
				String completionJson = buildCompletionJson(model, accumulatedContent.toString(), pt, ct);
				cacheService.storeResponse(chatRequest, servletRequest, ownerId, completionJson, pt, ct);
			}
		}
	}

	private void extractDeltaContent(String line, StringBuilder accumulator) {
		String delta = extractDelta(line);
		if (delta != null) {
			accumulator.append(delta);
		}
	}

	private @Nullable String extractDelta(String line) {
		if (line != null && line.startsWith("data: ") && !line.contains("[DONE]")) {
			String json = line.substring(6).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				JsonNode choices = node.path("choices");
				if (choices.isArray() && !choices.isEmpty()) {
					JsonNode delta = choices.get(0).path("delta");
					if (delta.has("content") && delta.get("content").isString()) {
						return delta.get("content").asString();
					}
				}
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	String replaceDeltaContent(String line, String newContent) {
		if (line != null && line.startsWith("data: ") && !line.contains("[DONE]")) {
			String json = line.substring(6).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				JsonNode choices = node.path("choices");
				if (choices.isArray() && !choices.isEmpty()) {
					JsonNode choice0 = choices.get(0);
					if (choice0 instanceof ObjectNode choiceObj) {
						JsonNode delta = choiceObj.path("delta");
						if (delta instanceof ObjectNode deltaObj) {
							deltaObj.put("content", newContent);
							return "data: " + objectMapper.writeValueAsString(node);
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		return line;
	}

	private String buildCompletionJson(String model, String content, int promptTokens, int completionTokens) {
		long created = Instant.now().getEpochSecond();
		String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		try {
			String escapedContent = objectMapper.writeValueAsString(content);
			return "{\"id\":\"" + id + "\",\"object\":\"chat.completion\",\"created\":" + created
					+ ",\"model\":\"" + model
					+ "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
					+ escapedContent + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":"
					+ promptTokens + ",\"completion_tokens\":" + completionTokens + ",\"total_tokens\":"
					+ (promptTokens + completionTokens) + "}}";
		} catch (Exception ex) {
			return "{\"id\":\"" + id + "\",\"object\":\"chat.completion\",\"created\":" + created
					+ ",\"model\":\"" + model
					+ "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":"
					+ promptTokens + ",\"completion_tokens\":" + completionTokens + ",\"total_tokens\":"
					+ (promptTokens + completionTokens) + "}}";
		}
	}

	private @Nullable OpenAiChatRequest parseChatRequest(String rawBody) {
		try {
			return objectMapper.readValue(rawBody, OpenAiChatRequest.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private void relayJson(
			ProviderResponse providerResponse,
			UUID requestId,
			@Nullable String ownerId,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable OpenAiChatRequest chatRequest,
			HttpServletRequest servletRequest,
			OutputStream out
	) throws IOException {
		StringBuilder payload = new StringBuilder();
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				payload.append(line).append('\n');
			}
		}
		String json = payload.toString().trim();
		JsonNode root;
		try {
			root = objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.warn("Upstream returned non-JSON 200 body from provider {}: {}", providerName, ex.getMessage());
			relayRawLine(out, json);
			return;
		}
		JsonNode normalized = normalizeCompletion(root, requestedModel, providerName);
		recordUsageAndCache(
				normalized, root, chatRequest, providerType, providerName, requestedModel,
				ownerId, requestId, servletRequest
		);
		relayRawLine(out, objectMapper.writeValueAsString(normalized));
	}

	private void relayRaw(ProviderResponse providerResponse, OutputStream out) {
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.write('\n');
			}
		}
		catch (IOException ex) {
			// The downstream client went away; the upstream stream is closed by
			// the try with resources, so nothing leaks and nothing is recorded.
			log.debug("Client disconnected while relaying the upstream error body");
		}
	}

	private void relayRawLine(OutputStream out, String body) throws IOException {
		out.write(body.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private JsonNode normalizeCompletion(JsonNode root, String requestedModel, String providerName) {
		ObjectNode normalized = objectMapper.createObjectNode();
		String id = root.path("id").isString() ? root.path("id").asString() : "chatcmpl-" + UUID.randomUUID();
		long created = root.path("created").isNumber() ? root.path("created").asLong() : Instant.now().getEpochSecond();
		normalized.put("id", id);
		normalized.put("object", "chat.completion");
		normalized.put("created", created);
		normalized.put("model", requestedModel);
		ArrayNode choices = normalized.putArray("choices");
		JsonNode upstreamChoices = root.path("choices");
		if (upstreamChoices.isArray() && upstreamChoices.size() > 0) {
			int index = 0;
			for (JsonNode choice : upstreamChoices) {
				String content = choice.path("message").path("content").isString()
						? choice.path("message").path("content").asString()
						: choice.path("text").isString() ? choice.path("text").asString() : "";
				String finish = choice.path("finish_reason").isString()
						? choice.path("finish_reason").asString() : "stop";
				ObjectNode out = choices.addObject();
				out.put("index", choice.path("index").isNumber() ? choice.path("index").asInt() : index);
				ObjectNode msg = out.putObject("message");
				msg.put("role", "assistant");
				msg.put("content", content);
				out.put("finish_reason", finish);
				index++;
			}
		} else {
			ObjectNode out = choices.addObject();
			out.put("index", 0);
			ObjectNode msg = out.putObject("message");
			msg.put("role", "assistant");
			msg.put("content", root.path("content").isString() ? root.path("content").asString() : "");
			out.put("finish_reason", "stop");
		}
		JsonNode usage = root.path("usage");
		if (usage.isObject()) {
			normalized.set("usage", usage);
		}
		return normalized;
	}

	private void recordUsageAndCache(
			JsonNode normalized,
			JsonNode upstreamRoot,
			@Nullable OpenAiChatRequest chatRequest,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable String ownerId,
			UUID requestId,
			HttpServletRequest servletRequest
	) {
		try {
			String normalizedJson = objectMapper.writeValueAsString(normalized);
			JsonNode usage = normalized.path("usage");
			long promptTokens = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asLong() : 0L;
			long completionTokens = usage.path("completion_tokens").isNumber()
					? usage.path("completion_tokens").asLong() : 0L;
			long costUsdMicros = costCalculator.calculate(
					providerType, requestedModel, promptTokens, completionTokens);
			eventPublisher.publishEvent(new TokenUsageEvent(
					requestId, ownerId, providerName, requestedModel,
					promptTokens, completionTokens,
					promptTokens + completionTokens,
					0L, costUsdMicros, Instant.now(),
					promptTokens, 0L, 0L, 0L,
					costUsdMicros, costUsdMicros, null
			));
			if (cacheService != null && chatRequest != null) {
				try {
					int pt = (int) Math.min(Integer.MAX_VALUE, promptTokens);
					int ct = (int) Math.min(Integer.MAX_VALUE, completionTokens);
					cacheService.storeResponse(
							chatRequest,
							servletRequest,
							ownerId,
							normalizedJson,
							pt,
							ct
					);
				} catch (RuntimeException ex) {
					log.debug("Cache store skipped for non-streaming completion: {}", ex.getMessage());
				}
			}
		} catch (JacksonException ex) {
			log.debug("Usage recording skipped for non-streaming completion: {}", ex.getMessage());
		}
	}

	private @Nullable String extractModel(String rawBody) {
		try {
			JsonNode root = objectMapper.readTree(rawBody);
			if (root == null || !root.isObject()) {
				return null;
			}
			JsonNode modelNode = root.get("model");
			return modelNode != null && modelNode.isString() ? modelNode.asString() : null;
		} catch (JacksonException ex) {
			return null;
		}
	}

	private boolean requestsUsage(String rawBody) {
		try {
			OpenAiChatRequest request = objectMapper.readValue(rawBody, OpenAiChatRequest.class);
			return request.requestsUsage();
		} catch (JacksonException ex) {
			return false;
		}
	}

	/**
	 * Wires the spend-budget enforcer when present. Optional on purpose: unit-constructed controllers keep working
	 * with budget enforcement silently skipped, exactly like unauthenticated paths never reach them.
	 *
	 * @param budgetEnforcer the enforcer, if available
	 */
	@Autowired(required = false)
	public void setBudgetEnforcer(BudgetEnforcer budgetEnforcer) {
		this.budgetEnforcer = budgetEnforcer;
	}

	/**
	 * Enforces spend budgets for one cache-miss request. Returns a 429 entity when a cap denies, a 503 when the
	 * budget service is unreachable (uniform fail-closed), or {@code null} to continue. Skipped (null) when no
	 * enforcer is wired — the bean is unconditional in production, so a null enforcer only occurs in non-Spring
	 * unit-test contexts; or when the key digest is absent (internal callers such as the semantic-cache warmer
	 * have no key to charge). A malformed digest is internal corruption and fails closed with 503.
	 */
	private @Nullable ResponseEntity<StreamingResponseBody> checkBudgetOrNull(
			ModelAlias alias, String model, @Nullable String ownerId, String trimmed,
			@Nullable String keyHashHex, @Nullable String idempotencyKey) {
		BudgetEnforcer enforcer = this.budgetEnforcer;
		if (enforcer == null || keyHashHex == null || keyHashHex.isBlank()) {
			return null;
		}
		ProviderType budgetType;
		try {
			var primary = alias.chain().getFirst();
			ProviderConfig primaryConfig = gatewayProperties.getProviders().get(primary.providerName());
			budgetType = primaryConfig == null ? ProviderType.OPENAI : primaryConfig.type();
		} catch (RuntimeException ex) {
			budgetType = ProviderType.OPENAI;
		}
		final SHA256Hash keyHash;
		try {
			keyHash = SHA256Hash.fromHex(keyHashHex);
		} catch (IllegalArgumentException malformed) {
			return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Budget service unavailable");
		}
		final BudgetDecision decision;
		try {
			decision = enforcer.checkBudget(keyHash, ownerId, budgetType, model,
					BudgetEnforcer.estimatePromptTokens(trimmed.length()), idempotencyKey);
		} catch (RateLimitUnavailableException unavailable) {
			return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Budget service unavailable");
		}
		if (decision instanceof BudgetDecision.Denied denied) {
			long retryAfter = Math.max(1L, denied.retryAfterSeconds());
			HttpHeaders denyHeaders = new HttpHeaders();
			denyHeaders.setContentType(MediaType.APPLICATION_JSON);
			denyHeaders.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
			denyHeaders.set("X-Budget-Remaining", "0");
			denyHeaders.set("X-Budget-Reset", Long.toString(System.currentTimeMillis() / 1000L + retryAfter));
			denyHeaders.set("X-Budget-Level", denied.level());
			denyHeaders.set("X-Budget-Window", denied.window());
			String denyBody = "{\"error\":{\"message\":\"budget exhausted (" + denied.level() + " "
					+ denied.window() + ")\"}}";
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(denyHeaders)
					.body(out -> out.write(denyBody.getBytes(StandardCharsets.UTF_8)));
		}
		return null;
	}

	private ResponseEntity<StreamingResponseBody> errorResponse(HttpStatus status, String message) {
		String body = "{\"error\":{\"message\":\"" + message + "\"}}";
		return ResponseEntity.status(status)
		                     .contentType(MediaType.APPLICATION_JSON)
		                     .body(out -> out.write(body.getBytes(StandardCharsets.UTF_8)));
	}

	private void writeSse(OutputStream out, String line) throws IOException {
		out.write(line.getBytes(StandardCharsets.UTF_8));
		out.write('\n');
	}

	private void writeSseError(OutputStream out, int limitBytes, int actualBytes, String provider) throws IOException {
		String json = "{\"code\":\"LINE_TOO_LONG\",\"message\":\"SSE line exceeds configured maximum of " + limitBytes
				+ " bytes (actual: " + actualBytes + ")\",\"limit\":" + limitBytes + ",\"actual\":" + actualBytes
				+ ",\"provider\":\"" + provider + "\"}";
		writeSse(out, "event: error");
		writeSse(out, "data: " + json);
		writeSse(out, "");
	}
}