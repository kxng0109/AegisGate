package io.github.kxng0109.aegisgate.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminFilterConfig")
class AdminFilterConfigTest {

	@Test
	@DisplayName("registers AdminAuthFilter with correct order and url patterns")
	void registersAdminFilterCorrectly() {
		AdminFilterConfig config = new AdminFilterConfig();
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<AdminAuthFilter> registration = config.adminAuthFilterRegistration(
				"secret-key",
				objectMapper
		);

		assertThat(registration.getOrder()).isEqualTo(1);
		assertThat(registration.getUrlPatterns()).containsExactly("/v1/admin/*");
		assertThat(registration.getFilter()).isNotNull();
	}
}
