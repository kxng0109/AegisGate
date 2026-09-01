package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Evaluates HTTP RFC 9111 caching headers, gateway extension headers, and request parameters to decide caching
 * eligibility, scope, and threshold overrides.
 */
@Component
@RequiredArgsConstructor
public class CachePolicyEngine {

	private final AegisCacheProperties properties;

	/**
	 * Determines whether cache lookup should be attempted for the incoming request.
	 *
	 * @param request     client chat request
	 * @param httpRequest servlet HTTP request
	 * @return true if cache evaluation is permitted, false to bypass
	 */
	public boolean shouldEvaluateCache(OpenAiChatRequest request, HttpServletRequest httpRequest) {
		if (!properties.isEnabled()) {
			return false;
		}

		String cacheControl = httpRequest.getHeader("Cache-Control");
		if (cacheControl != null) {
			String ccLower = cacheControl.toLowerCase(Locale.ROOT);
			if (ccLower.contains("no-store") || ccLower.contains("no-cache") || ccLower.contains("max-age=0")) {
				return false;
			}
		}

		String aegisNoCache = httpRequest.getHeader("X-Aegis-No-Cache");
		if ("true".equalsIgnoreCase(aegisNoCache)) {
			return false;
		}

		String cacheMode = httpRequest.getHeader("X-Aegis-Cache-Mode");
		if ("bypass".equalsIgnoreCase(cacheMode) || "write-only".equalsIgnoreCase(cacheMode)) {
			return false;
		}

		// Temperature gating: High temperature requests (> temperatureFloor) bypass semantic caching
		if (request.temperature() != null && request.temperature() > properties.getSemantic().getTemperatureFloor()) {
			String allowStochastic = httpRequest.getHeader("X-Aegis-Cache-Stochastic");
			return "true".equalsIgnoreCase(allowStochastic);
		}

		return true;
	}

	/**
	 * Determines whether the response should be saved to cache upon completion.
	 *
	 * @param request     client chat request
	 * @param httpRequest servlet HTTP request
	 * @return true if caching the response is permitted, false otherwise
	 */
	public boolean shouldStoreInCache(OpenAiChatRequest request, HttpServletRequest httpRequest) {
		if (!properties.isEnabled()) {
			return false;
		}

		String cacheControl = httpRequest.getHeader("Cache-Control");
		if (cacheControl != null && cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
			return false;
		}

		String cacheMode = httpRequest.getHeader("X-Aegis-Cache-Mode");
		if ("bypass".equalsIgnoreCase(cacheMode) || "read-only".equalsIgnoreCase(cacheMode)) {
			return false;
		}

		return true;
	}

	/**
	 * Resolves the multi-tenant isolation scope for the request.
	 *
	 * @param httpRequest servlet HTTP request
	 * @return resolved CacheScope
	 */
	public CacheScope resolveScope(HttpServletRequest httpRequest) {
		String scopeHeader = httpRequest.getHeader("X-Aegis-Cache-Scope");
		if (scopeHeader != null) {
			try {
				return CacheScope.valueOf(scopeHeader.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return properties.getDefaultScope();
	}

	/**
	 * Resolves any custom similarity threshold override from client headers.
	 *
	 * @param httpRequest servlet HTTP request
	 * @return threshold in range [0.0, 1.0]
	 */
	public double resolveSimilarityThreshold(HttpServletRequest httpRequest) {
		String thresholdHeader = httpRequest.getHeader("X-Aegis-Semantic-Threshold");
		if (thresholdHeader != null) {
			try {
				double parsed = Double.parseDouble(thresholdHeader.trim());
				if (parsed >= 0.50 && parsed <= 1.00) {
					return parsed;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return properties.getSemantic().getSimilarityThreshold();
	}
}
