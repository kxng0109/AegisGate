package io.github.kxng0109.aegisgate.proxy;

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
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
import java.time.Instant;
import java.util.List;
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
public class ProxyController {

	private final FailoverOrchestrator failoverOrchestrator;
	private final GatewayProperties gatewayProperties;
	private final ObjectMapper objectMapper;
	private final ProtocolAdapterResolver adapterResolver;
	private final CostCalculator costCalculator;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * @param failoverOrchestrator resolves the winning provider for a request
	 * @param gatewayProperties    provides the model aliases and providers
	 * @param objectMapper         parses the request body
	 * @param adapterResolver      picks the protocol adapter for a provider
	 * @param costCalculator       computes the cost of a completed stream
	 * @param eventPublisher       delivers the usage event to the ledger
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher
	) {
		this.failoverOrchestrator = failoverOrchestrator;
		this.gatewayProperties = gatewayProperties;
		this.objectMapper = objectMapper;
		this.adapterResolver = adapterResolver;
		this.costCalculator = costCalculator;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Proxies an OpenAI shaped chat completion request to the configured provider chain and streams the normalized SSE
	 * response back.
	 *
	 * @param rawBody the raw request body
	 * @param request the servlet request, used to read the authenticated owner
	 * @return a streaming response, or a JSON error for 400, 404, 502, 503, 504
	 */
	@PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> proxyChatCompletions(
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
		@Nullable String ownerId = (String) request.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.setCacheControl("no-cache");
		headers.set("X-Accel-Buffering", "no");

		return ResponseEntity.ok().headers(headers).body(out -> relaySse(
				providerResponse, adapter.newNormalizer(clientWantsUsage, model), out,
				requestId, ownerId, providerType, providerResponse.providerName(), model
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
			String requestedModel
	) throws IOException {
		long startedNanos = System.nanoTime();
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				List<String> normalized = normalizer.normalizeLine(line);
				for (String toWrite : normalized) {
					out.write(toWrite.getBytes(StandardCharsets.UTF_8));
					out.write('\n');
					out.flush();
				}
				if (normalizer.isDone()) {
					break;
				}
			}
		} catch (IOException ex) {
			// The downstream client went away; the upstream stream is closed by
			// the try with resources, so nothing leaks. Token usage cannot be
			// trusted on an aborted stream, so nothing is recorded.
			return;
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
		}
	}

	private void relayRaw(ProviderResponse providerResponse, OutputStream out) throws IOException {
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.write('\n');
			}
		} catch (IOException ex) {
			// The downstream client went away; the upstream stream is closed by
			// the try with resources, so nothing leaks and nothing is recorded.
		}
	}

	private String extractModel(String rawBody) {
		try {
			JsonNode root = objectMapper.readTree(rawBody);
			if (root == null || !root.isObject()) {
				return null;
			}
			JsonNode modelNode = root.get("model");
			return modelNode != null && modelNode.isTextual() ? modelNode.asText() : null;
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
}