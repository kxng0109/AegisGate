package io.github.kxng0109.aegisgate.security.filter;

import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitEngine;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the gateway servlet filters with an explicit, documented order.
 *
 * <p>Ordering rationale: the Boot reference states a filter that wraps the
 * servlet request must be registered at an order {@code <=}
 * {@code OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER} (0) so it runs after
 * the character-encoding filter  -  registering a request-wrapping filter at
 * {@code Ordered.HIGHEST_PRECEDENCE} would tie arbitrarily with
 * {@code OrderedCharacterEncodingFilter}. Hence:</p>
 * <ol>
 *   <li>{@link RequestBodyCachingFilter} at 0  -  wraps the request so the body
 *       can be read repeatedly.</li>
 *   <li>{@link KeyAuthFilter} at 1  -  authenticates and rate-limits using the
 *       buffered body, before the controller runs.</li>
 * </ol>
 *
 * <p>Both filters are plain {@code OncePerRequestFilter} classes (not
 * {@code @Component}s), so these registrations are their only registration.</p>
 */
@Configuration
public class SecurityFilterConfig {

	/**
	 * Registers the body-caching filter for {@code POST /v1/chat/completions}.
	 *
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<RequestBodyCachingFilter> requestBodyCachingFilterRegistration() {
		FilterRegistrationBean<RequestBodyCachingFilter> registration = new FilterRegistrationBean<>(
				new RequestBodyCachingFilter());
		registration.setOrder(RequestBodyCachingFilter.ORDER);
		registration.addUrlPatterns(RequestBodyCachingFilter.TARGET_PATH);
		registration.setName("aegisRequestBodyCachingFilter");
		return registration;
	}

	/**
	 * Registers the auth/rate-limit filter, after the body-caching filter.
	 *
	 * @param keyManagementService key lookup service
	 * @param rateLimitEngine      rate-limit engine
	 * @param objectMapper         Jackson mapper for body parsing
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<KeyAuthFilter> keyAuthFilterRegistration(
			KeyManagementService keyManagementService,
			RateLimitEngine rateLimitEngine,
			ObjectMapper objectMapper
	) {
		FilterRegistrationBean<KeyAuthFilter> registration = new FilterRegistrationBean<>(
				new KeyAuthFilter(keyManagementService, rateLimitEngine, objectMapper));
		registration.setOrder(KeyAuthFilter.ORDER);
		registration.addUrlPatterns(KeyAuthFilter.TARGET_PATH);
		registration.setName("aegisKeyAuthFilter");
		return registration;
	}
}