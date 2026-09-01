package io.github.kxng0109.aegisgate.proxy;

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
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import io.github.kxng0109.aegisgate.ledger.TokenUsageEvent;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapterResolver;
import io.github.kxng0109.aegisgate.proxy.protocol.SseNormalizer;
import io.github.kxng0109.aegisgate.proxy.sse.LineTooLongException;
import io.github.kxng0109.aegisgate.proxy.sse.SseConnectionLimitException;
import io.github.kxng0109.aegisgate.proxy.sse.SseFlushStrategy;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory;
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
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

	/**
	 * Primary constructor injecting all components including cache layer.
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
			@Nullable CachedStreamReconstitution cachedStreamReconstitution
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
	}

	/**
	 * Convenience constructor for existing tests without cache subsystem.
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

		ProviderResponse providerResponse;
		try {
			providerResponse = failoverOrchestrator.execute(alias, trimmed).join();
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
		UUID requestId = UUID.randomUUID();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.setCacheControl("no-cache");
		headers.set("X-Accel-Buffering", "no");

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
				io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType.from(providerType),
				providerName,
				java.util.UUID.randomUUID()
		);
		SseLineGuard.ProviderType guardProviderType = io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType.from(
				providerType);

		StringBuilder accumulatedContent = new StringBuilder();
		try {
			try (var lines = providerResponse.response().body()) {
				for (String line : (Iterable<String>) lines::iterator) {
					// Guard the raw upstream line before normalization
					List<String> guarded = lineGuard.checkLine(
							line,
							io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType.from(providerType)
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
						extractDeltaContent(toWrite, accumulatedContent);
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
			if (flushHandle != null) {
				flushStrategy.unregister(flushHandle);
			}
		}

		SseNormalizer.UsageInfo usage = normalizer.usage();
		if (usage != null) {
			long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
			String model = normalizer.upstreamModel() == null ? requestedModel : normalizer.upstreamModel();
			long costUsdMicros = costCalculator.calculate(
					providerType, model,
					usage.promptTokens(), usage.completionTokens()
			);
			eventPublisher.publishEvent(new TokenUsageEvent(
					requestId, ownerId, providerName, model,
					usage.promptTokens(), usage.completionTokens(),
					usage.promptTokens() + usage.completionTokens(),
					durationMs, costUsdMicros, Instant.now()
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
		if (line != null && line.startsWith("data: ") && !line.contains("[DONE]")) {
			String json = line.substring(6).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				JsonNode choices = node.path("choices");
				if (choices.isArray() && !choices.isEmpty()) {
					JsonNode delta = choices.get(0).path("delta");
					if (delta.has("content") && delta.get("content").isTextual()) {
						accumulator.append(delta.get("content").asText());
					}
				}
			} catch (Exception ignored) {
			}
		}
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

	private String extractModel(String rawBody) {
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