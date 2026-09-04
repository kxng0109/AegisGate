package io.github.kxng0109.aegisgate.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiConfig")
class OpenApiConfigTest {

	private final OpenApiConfig openApiConfig = new OpenApiConfig();

	@Test
	@DisplayName("aegisGateOpenAPI registers complete OpenAPI 3.1 metadata and security components")
	void aegisGateOpenAPI() {
		OpenAPI openAPI = openApiConfig.aegisGateOpenAPI();

		assertThat(openAPI.getOpenapi()).isEqualTo("3.1.0");
		assertThat(openAPI.getInfo()).isNotNull();
		assertThat(openAPI.getInfo().getTitle()).contains("AegisGate");
		assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.4.0");
		assertThat(openAPI.getInfo().getLicense().getIdentifier()).isEqualTo("Apache-2.0");
		assertThat(openAPI.getInfo().getContact()).isNotNull();
		assertThat(openAPI.getServers()).hasSize(2);

		// Security schemes in components
		assertThat(openAPI.getComponents().getSecuritySchemes()).containsKeys(
				OpenApiConfig.SCHEME_BEARER_AUTH,
				OpenApiConfig.SCHEME_ADMIN_KEY_HEADER,
				OpenApiConfig.SCHEME_ADMIN_BEARER
		);

		SecurityScheme bearerScheme = openAPI.getComponents().getSecuritySchemes()
		                                     .get(OpenApiConfig.SCHEME_BEARER_AUTH);
		assertThat(bearerScheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
		assertThat(bearerScheme.getScheme()).isEqualTo("bearer");

		SecurityScheme adminKeyScheme = openAPI.getComponents().getSecuritySchemes()
		                                       .get(OpenApiConfig.SCHEME_ADMIN_KEY_HEADER);
		assertThat(adminKeyScheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
		assertThat(adminKeyScheme.getName()).isEqualTo("X-Admin-Key");
	}

	@Test
	@DisplayName("GroupedOpenApi beans configure paths and display names correctly")
	void groupedApis() {
		GroupedOpenApi publicApi = openApiConfig.publicGatewayApi();
		assertThat(publicApi.getGroup()).isEqualTo("1-public-gateway");
		assertThat(publicApi.getDisplayName()).contains("Public Gateway");
		assertThat(publicApi.getPathsToMatch()).containsExactly("/v1/chat/**", "/v1/embeddings/**", "/v1/mcp/**");

		OpenAPI publicOpenApi = new OpenAPI();
		publicApi.getOpenApiCustomizers().forEach(c -> c.customise(publicOpenApi));
		assertThat(publicOpenApi.getSecurity()).isNotEmpty();

		GroupedOpenApi adminApi = openApiConfig.adminPortalApi();
		assertThat(adminApi.getGroup()).isEqualTo("2-admin-portal");
		assertThat(adminApi.getDisplayName()).contains("Administrative");
		assertThat(adminApi.getPathsToMatch()).containsExactly("/v1/admin/**");

		OpenAPI adminOpenApi = new OpenAPI();
		adminApi.getOpenApiCustomizers().forEach(c -> c.customise(adminOpenApi));
		assertThat(adminOpenApi.getSecurity()).isNotEmpty();

		GroupedOpenApi actuatorApi = openApiConfig.actuatorApi();
		assertThat(actuatorApi.getGroup()).isEqualTo("3-observability");
		assertThat(actuatorApi.getDisplayName()).contains("Observability");
		assertThat(actuatorApi.getPathsToMatch()).containsExactly("/actuator/**");
	}
}
