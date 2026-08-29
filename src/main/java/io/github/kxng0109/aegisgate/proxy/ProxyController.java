package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.aegisgate.proxy.failover.ProviderResponse;
import io.github.kxng0109.aegisgate.proxy.failover.UpstreamUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

/**
 * REST controller exposing the chat completions proxy endpoint.
 *
 * <p>The client always talks to this one OpenAI shaped endpoint. Behind it the
 * controller resolves the requested model to a {@link ModelAlias}, asks the
 * {@link FailoverOrchestrator} to pick a winning provider, and relays that
 * provider's SSE stream back with zero buffering. Failover has already
 * happened by the time streaming begins, so the client never sees a switch.</p>
 */
@Slf4j
@RestController
public class ProxyController {

	private static final String DONE_MARKER = "data: [DONE]";

	private final FailoverOrchestrator failoverOrchestrator;
	private final GatewayProperties gatewayProperties;
	private final ObjectMapper objectMapper;

	/**
	 * @param failoverOrchestrator resolves the winning provider for a request
	 * @param gatewayProperties    provides the model aliases
	 * @param objectMapper         parses the model name from the request body
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper
	) {
		this.failoverOrchestrator = failoverOrchestrator;
		this.gatewayProperties = gatewayProperties;
		this.objectMapper = objectMapper;
	}

	/**
	 * Proxies an OpenAI shaped chat completion request to the configured
	 * provider chain and streams the SSE response back.
	 *
	 * @param rawBody the raw request body
	 * @return a streaming response, or a JSON error for 400, 404, 502, 503, 504
	 */
	@PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> proxyChatCompletions(@RequestBody String rawBody) {
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
			throw new UpstreamUnavailableException("upstream request failed unexpectedly",
			                                       cause, false, false
			);
		}

		int status = providerResponse.response().statusCode();
		if (status != HttpStatus.OK.value()) {
			return ResponseEntity.status(status)
			                     .contentType(MediaType.APPLICATION_JSON)
			                     .body(out -> relayRaw(providerResponse, out));
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.setCacheControl("no-cache");
		headers.set("X-Accel-Buffering", "no");

		return ResponseEntity.ok().headers(headers).body(out -> relaySse(providerResponse, out));
	}

	private void relaySse(ProviderResponse providerResponse, OutputStream out) throws IOException {
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.write('\n');
				out.flush();
				if (DONE_MARKER.equals(line.trim())) {
					break;
				}
			}
		} catch (IOException ex) {
			// The downstream client went away; the upstream stream is closed by
			// the try with resources, so nothing leaks and nothing is recorded.
		}
	}

	private void relayRaw(ProviderResponse providerResponse, OutputStream out) throws IOException {
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.write('\n');
			}
		}
		catch (IOException ex) {
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

	private ResponseEntity<StreamingResponseBody> errorResponse(HttpStatus status, String message) {
		String body = "{\"error\":{\"message\":\"" + message + "\"}}";
		return ResponseEntity.status(status)
		                     .contentType(MediaType.APPLICATION_JSON)
		                     .body(out -> out.write(body.getBytes(StandardCharsets.UTF_8)));
	}
}