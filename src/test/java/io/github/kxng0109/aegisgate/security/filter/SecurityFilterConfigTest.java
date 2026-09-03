package io.github.kxng0109.aegisgate.security.filter;

import io.github.kxng0109.aegisgate.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.aegisgate.security.guardrail.injection.PromptInjectionScanner;
import io.github.kxng0109.aegisgate.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.aegisgate.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SecurityFilterConfig Tests")
class SecurityFilterConfigTest {

	private final SecurityFilterConfig config = new SecurityFilterConfig();

	@Test
	@DisplayName("registers RequestBodyCachingFilter at order 0 with correct path and name")
	void registersRequestBodyCachingFilter() {
		FilterRegistrationBean<RequestBodyCachingFilter> reg = config.requestBodyCachingFilterRegistration();

		assertThat(reg.getOrder()).isEqualTo(0);
		assertThat(reg.getUrlPatterns()).containsExactly("/v1/chat/completions");
		assertThat(reg.getFilter()).isInstanceOf(RequestBodyCachingFilter.class);
	}

	@Test
	@DisplayName("registers KeyAuthFilter at order 1 with correct path and name")
	void registersKeyAuthFilter() {
		KeyManagementService keyManagementService = mock(KeyManagementService.class);
		RateLimitEngine rateLimitEngine = mock(RateLimitEngine.class);
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<KeyAuthFilter> reg = config.keyAuthFilterRegistration(
				keyManagementService, rateLimitEngine, objectMapper
		);

		assertThat(reg.getOrder()).isEqualTo(1);
		assertThat(reg.getUrlPatterns()).containsExactly("/v1/chat/completions");
		assertThat(reg.getFilter()).isInstanceOf(KeyAuthFilter.class);
	}

	@Test
	@DisplayName("registers IngressSecurityFilter at order 2 with correct path and name")
	void registersIngressSecurityFilter() {
		IngressSecretScanner secretScanner = mock(IngressSecretScanner.class);
		PromptInjectionScanner injectionScanner = mock(PromptInjectionScanner.class);
		PiiAnonymizer piiAnonymizer = mock(PiiAnonymizer.class);
		GuardrailProperties properties = new GuardrailProperties();
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<IngressSecurityFilter> reg = config.ingressSecurityFilterRegistration(
				secretScanner, injectionScanner, piiAnonymizer, properties, objectMapper
		);

		assertThat(reg.getOrder()).isEqualTo(2);
		assertThat(reg.getUrlPatterns()).containsExactly("/v1/chat/completions");
		assertThat(reg.getFilter()).isInstanceOf(IngressSecurityFilter.class);
	}
}
