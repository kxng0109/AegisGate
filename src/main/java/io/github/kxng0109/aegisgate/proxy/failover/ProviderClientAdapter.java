package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapter;
import io.github.kxng0109.aegisgate.proxy.protocol.ProtocolAdapterResolver;
import io.github.kxng0109.aegisgate.proxy.sse.BoundedLineBodyHandler;
import io.github.kxng0109.aegisgate.proxy.sse.SseLineGuardAutoConfig;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Turns one provider attempt into a non blocking HTTP call.
 *
 * <p>The request is assembled by the {@link ProtocolAdapter} for the
 * provider's dialect, so the same attempt path serves OpenAI compatible providers, Anthropic, and Ollama. The request
 * carries the provider's own timeout, which per the JDK semantics bounds the time to the first byte of the response
 * rather than the duration of a long lived SSE stream. That makes it exactly the per attempt failover bound the
 * orchestrator needs.</p>
 */
@Service
@RequiredArgsConstructor
public class ProviderClientAdapter {

	private final HttpClient proxyHttpClient;
	private final ProtocolAdapterResolver adapterResolver;
	private final SseLineGuardAutoConfig.SseLineGuardFactory lineGuardFactory;

	/**
	 * Sends the chat completion request to the provider without blocking.
	 *
	 * <p>The returned future completes when the response headers arrive, with
	 * the body available as a lazy stream of lines. On timeout or transport failure it completes exceptionally, which
	 * the orchestrator classifies as a transient failure.</p>
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
		ProtocolAdapter adapter = adapterResolver.resolve(config.type());

		HttpRequest.Builder builder = HttpRequest.newBuilder(adapter.buildUpstreamUrl(config))
		                                         .timeout(config.requestTimeout())
		                                         .POST(HttpRequest.BodyPublishers.ofString(
				                                         adapter.buildRequestBody(requestBody, modelOverride),
				                                         StandardCharsets.UTF_8
		                                         ));
		adapter.buildRequestHeaders(config).forEach(builder::header);

		BoundedLineBodyHandler handler = lineGuardFactory.bodyHandlerForProvider(
				io.github.kxng0109.aegisgate.proxy.sse.SseLineGuard.ProviderType.from(config.type())
		);
		return proxyHttpClient.sendAsync(builder.build(), handler);
	}
}