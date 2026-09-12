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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SecurityFilterConfig Tests")
class SecurityFilterConfigTest {

	private final SecurityFilterConfig config = new SecurityFilterConfig();

	@Test
	@DisplayName("registers RequestBodyCachingFilter at order 0 for chat and embeddings")
	void registersRequestBodyCachingFilter() {
		FilterRegistrationBean<RequestBodyCachingFilter> reg =
				config.requestBodyCachingFilterRegistration(1_048_576);

		assertThat(reg.getOrder()).isEqualTo(0);
		assertThat(reg.getUrlPatterns())
				.containsExactlyInAnyOrder(
						RequestBodyCachingFilter.TARGET_PATH_CHAT,
						RequestBodyCachingFilter.TARGET_PATH_EMBEDDINGS);
		assertThat(reg.getFilter()).isInstanceOf(RequestBodyCachingFilter.class);
	}

	@Test
	@DisplayName("registers KeyAuthFilter at order 1 for chat and embeddings")
	void registersKeyAuthFilter() {
		KeyManagementService keyManagementService = mock(KeyManagementService.class);
		RateLimitEngine rateLimitEngine = mock(RateLimitEngine.class);
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<KeyAuthFilter> reg = config.keyAuthFilterRegistration(
				keyManagementService, rateLimitEngine, objectMapper
		);

		assertThat(reg.getOrder()).isEqualTo(1);
		assertThat(reg.getUrlPatterns())
				.containsExactlyInAnyOrder(
						KeyAuthFilter.TARGET_PATH_CHAT,
						KeyAuthFilter.TARGET_PATH_EMBEDDINGS);
		assertThat(reg.getFilter()).isInstanceOf(KeyAuthFilter.class);
	}

	@Test
	@DisplayName("registers IngressSecurityFilter at order 2 for chat only")
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
		assertThat(reg.getUrlPatterns()).containsExactly(IngressSecurityFilter.TARGET_PATH);
		assertThat(reg.getFilter()).isInstanceOf(IngressSecurityFilter.class);
	}

	@Test
	@DisplayName("gateway filters cover chat and embeddings, nothing else")
	void filtersCoverDeclaredRoutes() {
		assertThat(List.of(
				RequestBodyCachingFilter.TARGET_PATH_CHAT,
				RequestBodyCachingFilter.TARGET_PATH_EMBEDDINGS,
				KeyAuthFilter.TARGET_PATH_CHAT,
				KeyAuthFilter.TARGET_PATH_EMBEDDINGS,
				IngressSecurityFilter.TARGET_PATH))
				.contains("/v1/chat/completions", "/v1/embeddings")
				.doesNotContain("/v1/admin", "/actuator", "/v3/api-docs");
	}
}
