package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.UpstreamConfig;

import java.util.UUID;

/**
 * Immutable request data for a single upstream proxy call.
 *
 * <p>Encapsulates the raw client request body, the resolved upstream configuration,
 * and a unique correlation identifier for tracing and logging.
 */
public record ProxyRequest(
		String requestBody,
		UpstreamConfig upstreamConfig,
		UUID requestId
) {
	public ProxyRequest {
		if (requestBody == null || requestBody.isBlank()) {
			throw new IllegalArgumentException("requestBody required!");
		}
		if (upstreamConfig == null) {
			throw new NullPointerException("upstreamConfig required!");
		}
		if (requestId == null) {
			throw new NullPointerException("requestId required!");
		}
	}
}