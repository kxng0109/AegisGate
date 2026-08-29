package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Turns one provider attempt into a non blocking HTTP call.
 *
 * <p>The request carries the provider's own timeout, which per the JDK
 * semantics bounds the time to the first byte of the response rather than the
 * duration of a long lived SSE stream. That makes it exactly the per attempt
 * failover bound the orchestrator needs.</p>
 *
 * <p>Keyless providers (for example a local Ollama instance) are supported:
 * the Authorization header is only sent when a key is configured. When a
 * chain step carries a model override, the request body is re serialized with
 * that model name before sending, so aliases can remap models per provider.</p>
 */
@Service
@RequiredArgsConstructor
public class ProviderClientAdapter {

	/**
	 * The OpenAI compatible path every Phase 3 provider is assumed to speak.
	 */
	public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

	private final HttpClient proxyHttpClient;
	private final ObjectMapper objectMapper;

	/**
	 * Sends the chat completion request to the provider without blocking.
	 *
	 * <p>The returned future completes when the response headers arrive, with
	 * the body available as a lazy stream of lines. On timeout or transport
	 * failure it completes exceptionally, which the orchestrator classifies as
	 * a transient failure.</p>
	 *
	 * @param config        the provider to contact
	 * @param requestBody   the client request body, OpenAI shaped
	 * @param modelOverride optional model remapping for this step, may be {@code null}
	 * @return the future of the streaming response
	 */
	public CompletableFuture<HttpResponse<Stream<String>>> sendAsync(
			ProviderConfig config,
			String requestBody,
			@Nullable String modelOverride
	) {
		String body = modelOverride == null ? requestBody : withModel(requestBody, modelOverride);
		String baseUrl = config.baseUrl().toString();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + CHAT_COMPLETIONS_PATH))
		                                         .timeout(config.requestTimeout())
		                                         .header("Content-Type", "application/json")
		                                         .POST(HttpRequest.BodyPublishers.ofString(body,
		                                                                                   StandardCharsets.UTF_8
		                                         ));

		String apiKey = config.apiKey() == null ? "" : config.apiKey().value();
		if (!apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}

		return proxyHttpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines());
	}

	private String withModel(String requestBody, String modelOverride) {
		JsonNode root = objectMapper.readTree(requestBody);
		ObjectNode rewritten = (ObjectNode) root.deepCopy();
		rewritten.put("model", modelOverride);
		return objectMapper.writeValueAsString(rewritten);
	}
}