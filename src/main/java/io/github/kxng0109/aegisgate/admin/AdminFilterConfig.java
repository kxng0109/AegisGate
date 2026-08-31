package io.github.kxng0109.aegisgate.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers servlet filters for administrative endpoints under {@code /v1/admin/**}.
 */
@Configuration
public class AdminFilterConfig {

	/**
	 * Registers the admin authentication filter with high precedence.
	 *
	 * @param masterKey    the configured master admin secret
	 * @param objectMapper JSON serializer for RFC 9457 Problem Details responses
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(
			@Value("${gateway.admin.master-key:}") String masterKey,
			ObjectMapper objectMapper
	) {
		FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(
				new AdminAuthFilter(masterKey, objectMapper)
		);
		registration.setOrder(1);
		registration.addUrlPatterns("/v1/admin/*");
		registration.setName("aegisAdminAuthFilter");
		return registration;
	}
}
