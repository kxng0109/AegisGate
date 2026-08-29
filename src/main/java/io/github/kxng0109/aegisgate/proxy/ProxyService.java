package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import io.github.kxng0109.aegisgate.security.HeaderSanitizer;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Core proxy service that forwards requests to the configured upstream provider
 * and streams the SSE response back.
 *
 * <p>Owns a shared {@link HttpClient} configured with virtual-thread executor,
 * connect timeout, HTTP/2, and redirect disabling for SSRF defense.
 * Performs startup SSRF validation of the configured upstream URL.
 */
@Service
public class ProxyService {

	private final HttpClient httpClient;
	private final UpstreamConfig upstreamConfig;
	private final SsrfValidator ssrfValidator;
	private final HeaderSanitizer headerSanitizer;

	/**
	 * Creates a new proxy service with the configured dependencies.
	 *
	 * @param httpClient      shared HTTP client configured with VT executor and timeouts
	 * @param upstreamConfig  upstream provider configuration
	 * @param ssrfValidator   SSRF validator for URL safety checks
	 * @param headerSanitizer header sanitizer for request/response header processing
	 */
	public ProxyService(
			HttpClient httpClient,
			UpstreamConfig upstreamConfig,
			SsrfValidator ssrfValidator,
			HeaderSanitizer headerSanitizer
	) {
		this.httpClient = httpClient;
		this.upstreamConfig = upstreamConfig;
		this.ssrfValidator = ssrfValidator;
		this.headerSanitizer = headerSanitizer;
	}

	/**
	 * Validates the configured upstream URL at startup to fail fast on misconfiguration.
	 *
	 * @throws SsrfViolationException if the upstream URL resolves to a blocked address
	 */
	@PostConstruct
	void init() {
		ssrfValidator.validate(URI.create(upstreamConfig.baseUrl()));
	}

	/**
	 * Proxies a request to the upstream provider and returns the streaming response.
	 *
	 * <p>Performs per-request SSRF validation, builds the upstream HTTP request with
	 * sanitized headers and the configured request timeout, and streams the response
	 * body as a line-delimited {@link Stream} for zero-buffer SSE passthrough.
	 *
	 * @param request       the proxy request containing body, config, and correlation ID
	 * @param clientHeaders map of client request headers (first value per name)
	 * @return upstream HTTP response with line-streamed body
	 * @throws IOException            if an I/O error occurs
	 * @throws InterruptedException   if the thread is interrupted
	 * @throws SsrfViolationException if the upstream URL fails SSRF validation
	 */
	public HttpResponse<Stream<String>> proxy(ProxyRequest request, Map<String, String> clientHeaders)
			throws IOException, InterruptedException, SsrfViolationException {

		ssrfValidator.validate(URI.create(upstreamConfig.baseUrl()));

		String baseUrl = upstreamConfig.baseUrl();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		URI upstreamUrl = URI.create(baseUrl + upstreamConfig.chatCompletionsPath());

		Map<String, String> sanitizedHeaders = headerSanitizer.sanitizeRequestHeaders(clientHeaders);

		String[] headerArray = new String[sanitizedHeaders.size() * 2];
		int i = 0;
		for (Map.Entry<String, String> entry : sanitizedHeaders.entrySet()) {
			headerArray[i++] = entry.getKey();
			headerArray[i++] = entry.getValue();
		}

		HttpRequest httpRequest = HttpRequest.newBuilder(upstreamUrl)
		                                     .POST(
				                                     HttpRequest.BodyPublishers.ofString(
						                                     request.requestBody(),
						                                     StandardCharsets.UTF_8
				                                     )
		                                     )
		                                     .headers(headerArray)
		                                     .timeout(request.upstreamConfig().requestTimeout())
		                                     .build();

		return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
	}
}