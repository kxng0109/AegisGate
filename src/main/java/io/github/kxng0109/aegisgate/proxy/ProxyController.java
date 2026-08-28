package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import io.github.kxng0109.aegisgate.security.HeaderSanitizer;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REST controller exposing the proxy endpoint for chat completions.
 *
 * <p>Accepts OpenAI-compatible chat completion requests, validates and sanitizes them,
 * forwards to the upstream provider via {@link ProxyService}, and streams the SSE
 * response back to the client with zero buffering.
 */
@RestController
public class ProxyController {

	private final ProxyService proxyService;
	private final UpstreamConfig upstreamConfig;
	private final HeaderSanitizer headerSanitizer;
	private final SsrfValidator ssrfValidator;

	/**
	 * Creates a new proxy controller with the required dependencies.
	 *
	 * @param proxyService    service for executing upstream proxy requests
	 * @param upstreamConfig  upstream provider configuration
	 * @param headerSanitizer header sanitizer for request/response processing
	 * @param ssrfValidator   SSRF validator for URL safety checks
	 */
	public ProxyController(
			ProxyService proxyService,
			UpstreamConfig upstreamConfig,
			HeaderSanitizer headerSanitizer,
			SsrfValidator ssrfValidator
	) {
		this.proxyService = proxyService;
		this.upstreamConfig = upstreamConfig;
		this.headerSanitizer = headerSanitizer;
		this.ssrfValidator = ssrfValidator;
	}

	/**
	 * Handles chat completion requests by proxying to the upstream provider.
	 *
	 * <p>Validates request body, runs SSRF check, delegates to {@link ProxyService},
	 * and streams the SSE response back with appropriate headers.
	 *
	 * @param rawBody raw JSON request body from the client
	 * @return streaming response entity with SSE content
	 */
	@PostMapping(
			value = "/v1/chat/completions",
			consumes = MediaType.APPLICATION_JSON_VALUE
	)
	public ResponseEntity<StreamingResponseBody> proxy(
			@RequestBody String rawBody,
			@RequestHeader Map<String, String> clientHeaders
	) {
		if (rawBody == null || rawBody.isBlank()) {
			return ResponseEntity.badRequest().body(
					out -> out.write("Body required!".getBytes(StandardCharsets.UTF_8))
			);
		}

		UUID requestId = UUID.randomUUID();

		// Per-request SSRF validation
		try {
			ssrfValidator.validate(URI.create(upstreamConfig.baseUrl()));
		} catch (SsrfViolationException e) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
			                     .body(out -> out.write(e.getMessage().getBytes(StandardCharsets.UTF_8)));
		}

		ProxyRequest request = new ProxyRequest(rawBody, upstreamConfig, requestId);

		try {
			HttpResponse<Stream<String>> response = proxyService.proxy(request, clientHeaders);

			int statusCode = response.statusCode();

			// Convert upstream HttpHeaders to Map<String, String> (first value)
			Map<String, String> upstreamHeaders = response.headers().map().entrySet().stream()
			                                              .collect(
					                                              Collectors.toMap(
							                                              Map.Entry::getKey,
							                                              e -> e.getValue().getFirst(),
							                                              (String v1, String v2) -> v1,
							                                              LinkedHashMap::new
					                                              )
			                                              );

			Map<String, String> sanitizedResponseHeaders = headerSanitizer.sanitizeResponseHeaders(upstreamHeaders);
			sanitizedResponseHeaders.put("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE);

			if (statusCode != 200) {
				// Non-200: read error body and stream it
				final Stream<String> errorStream = response.body();
				StreamingResponseBody errorBody = out -> {
					try (var stream = errorStream) {
						for (String line : (Iterable<String>) stream::iterator) {
							out.write(line.getBytes(StandardCharsets.UTF_8));
							out.write('\n');
							out.flush();
						}
					} catch (IOException ignored) {
						// Client abort
					}
				};
				return ResponseEntity.status(statusCode)
				                     .headers(httpHeadersFromMap(sanitizedResponseHeaders))
				                     .body(errorBody);
			}

			// Success: stream SSE response
			final Stream<String> successStream = response.body();
			StreamingResponseBody successBody = out -> {
				try (var stream = successStream) {
					for (String line : (Iterable<String>) stream::iterator) {
						out.write(line.getBytes(StandardCharsets.UTF_8));
						out.write('\n');
						out.flush();
					}
				} catch (IOException ignored) {
					// Client aborted - stream closes automatically
				}
			};
			return ResponseEntity.ok()
			                     .headers(httpHeadersFromMap(sanitizedResponseHeaders))
			                     .body(successBody);

		} catch (java.net.http.HttpTimeoutException e) {
			return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
			                     .body(out -> out.write(
					                     ("Gateway timeout: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
			                     );
		} catch (UnknownHostException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			                     .body(out -> out.write(
					                     ("Unknown host: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
			                     );
		} catch (SsrfViolationException e) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
			                     .body(out -> out.write(e.getMessage().getBytes(StandardCharsets.UTF_8))
			                     );
		} catch (IOException | InterruptedException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			                     .body(out -> out.write(
					                     ("Upstream error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
			                     );
		}
	}

	/**
	 * Converts a string map to Spring HttpHeaders.
	 *
	 * @param map source header map
	 * @return Spring HttpHeaders instance
	 */
	private HttpHeaders httpHeadersFromMap(Map<String, String> map) {
		HttpHeaders headers = new HttpHeaders();
		map.forEach(headers::set);
		return headers;
	}
}