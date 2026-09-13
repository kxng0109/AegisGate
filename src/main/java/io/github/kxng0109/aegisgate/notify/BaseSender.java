package io.github.kxng0109.aegisgate.notify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared outbound-POST mechanics for notification channels: SSRF-validated targets, short timeouts, secret
 * references resolved from the runtime environment (never from the database or the repo), and uniform
 * status classification (2xx sent; 429/5xx/timeout transient; anything else terminal).
 */
public abstract class BaseSender {

	private static final Pattern SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

	private static final Logger log = LoggerFactory.getLogger(BaseSender.class);

	protected final SsrfValidator ssrfValidator;

	protected final HttpClient httpClient;

	protected final ObjectMapper objectMapper;

	protected BaseSender(SsrfValidator ssrfValidator) {
		this(ssrfValidator, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				new ObjectMapper());
	}

	BaseSender(SsrfValidator ssrfValidator, HttpClient httpClient, ObjectMapper objectMapper) {
		this.ssrfValidator = ssrfValidator;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * Resolves a secret reference to its runtime value.
	 *
	 * @param ref environment variable name, possibly {@code null}
	 * @return the secret value, or {@code null} when absent or malformed (terminal config error downstream)
	 */
	protected static @Nullable String resolveSecret(@Nullable String ref) {
		if (ref == null || ref.isBlank() || !SECRET_REF.matcher(ref.trim()).matches()) {
			return null;
		}
		String value = System.getenv(ref.trim());
		return value == null || value.isEmpty() ? null : value;
	}

	/**
	 * POSTs a JSON-serializable body, classifying the outcome. URLs are SSRF-validated first; validation
	 * failure is terminal (never retried, never contacted).
	 */
	protected ChannelResult postJson(String target, Object body, Map<String, String> headers) {
		final String json;
		try {
			json = objectMapper.writeValueAsString(body);
		} catch (Exception ex) {
			log.warn("Notification serialization failed; dropping");
			return ChannelResult.TERMINAL;
		}
		return postRaw(target, json, headers);
	}

	/**
	 * POSTs pre-serialized bytes (for senders that must sign the exact bytes, like the HMAC webhook).
	 */
	protected ChannelResult postRaw(String target, String json, Map<String, String> headers) {
		final URI uri;
		try {
			uri = URI.create(target.trim());
		} catch (IllegalArgumentException malformed) {
			log.warn("Notification target malformed; dropping");
			return ChannelResult.TERMINAL;
		}
		try {
			ssrfValidator.validate(uri);
		} catch (SsrfViolationException violation) {
			log.warn("Notification target blocked: {}", violation.getMessage());
			return ChannelResult.TERMINAL;
		}
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder()
			                                         .uri(uri)
			                                         .timeout(Duration.ofSeconds(10))
			                                         .header("Content-Type", "application/json")
			                                         .POST(HttpRequest.BodyPublishers.ofString(json));
			for (Map.Entry<String, String> header : headers.entrySet()) {
				request.header(header.getKey(), header.getValue());
			}
			int status = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status >= 200 && status < 300) {
				return ChannelResult.SENT;
			}
			if (status == 429 || status >= 500) {
				return ChannelResult.TRANSIENT;
			}
			return ChannelResult.TERMINAL;
		} catch (IOException ex) {
			return ChannelResult.TRANSIENT;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ChannelResult.TERMINAL;
		} catch (RuntimeException ex) {
			log.warn("Notification POST failed unexpectedly; treating as transient");
			return ChannelResult.TRANSIENT;
		}
	}
}
