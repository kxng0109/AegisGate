package io.github.kxng0109.aegisgate.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.1 and Swagger UI configuration for AegisGate AI Gateway. Configures global metadata, security schemes for
 * Virtual Keys and Admin Master Keys, tag definitions, and GroupedOpenApi partitions.
 */
@Configuration
public class OpenApiConfig {

	public static final String SCHEME_BEARER_AUTH = "BearerAuth";
	public static final String SCHEME_ADMIN_KEY_HEADER = "AdminKeyAuth";
	public static final String SCHEME_ADMIN_BEARER = "AdminBearerAuth";

	@Value("${server.port:8080}")
	private String serverPort;

	/**
	 * Primary OpenAPI specification definition with metadata and security components.
	 *
	 * @return configured OpenAPI object
	 */
	@Bean
	public OpenAPI aegisGateOpenAPI() {
		return new OpenAPI()
				.openapi("3.1.0")
				.info(new Info()
						      .title("AegisGate AI Gateway & Resilient Reverse Proxy")
						      .version("1.1.0")
						      .summary(
								      "Enterprise-grade AI proxy gateway with rate limiting, multi-tier caching, universal tool calling, and failover")
						      .description("""
								                   **AegisGate** is a high-performance, resilient AI reverse proxy gateway engineered in Java 25 & Spring Boot 4.1.
								                   
								                   ### Key Capabilities:
								                   * **Universal Protocol Normalization**: Relays chat completions and embeddings across OpenAI, Anthropic Claude, Google Gemini Developer API, Google Cloud Vertex AI, DeepSeek (V3/R1/V4), Cohere, and Ollama.
								                   * **Universal Tool & Function Calling**: Normalizes OpenAI tool declarations across Anthropic (`input_schema`), Gemini OpenAPI uppercase formats (`OBJECT`, `STRING`, `INTEGER`), and DeepSeek reasoning streams.
								                   * **Multi-Tier Caching**: Sub-millisecond L0 in-memory Caffeine + L1 Redis exact matching + L2 RediSearch HNSW vector similarity search with anti-hallucination guardrails.
								                   * **Transparent Auto-Batching**: Automatically partitions large embedding workloads across upstream provider limits concurrently on Virtual Threads.
								                   * **Zero-Buffer SSE Streaming**: Backpressure-guarded Server-Sent Events relay with synthetic stream replay on cache hits.
								                   * **Distributed Rate Limiting**: Atomic Lua token-bucket algorithm in Redis with dual RPM & TPM enforcement.
								                   * **Asynchronous Usage Ledger**: Exact micro-dollar precision accounting and PostgreSQL 16+ covering-index analytical aggregations.
								                   """)
						      .contact(new Contact()
								               .name("AegisGate Systems & Security Engineering")
								               .url("https://github.com/kxng0109/AegisGate")
								               .email("security@aegisgate.internal"))
						      .license(new License()
								               .name("Apache 2.0")
								               .identifier("Apache-2.0")
								               .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
				.servers(List.of(
						new Server()
								.url("http://localhost:" + serverPort)
								.description("Local Development Server"),
						new Server()
								.url("https://gateway.aegisgate.io")
								.description("Production Gateway Cluster Edge")
				))
				.components(new Components()
						            // 1. Virtual API Key Auth (Authorization: Bearer gw-...)
						            .addSecuritySchemes(
								            SCHEME_BEARER_AUTH, new SecurityScheme()
										            .type(SecurityScheme.Type.HTTP)
										            .scheme("bearer")
										            .bearerFormat("gw-* (AegisGate Key)")
										            .description("""
												                         Virtual API Key authentication for client inference consumers.
												                         Format: `Authorization: Bearer gw-<32-char-random-token>`
												                         """)
						            )
						            // 2. Admin Header Key Auth (X-Admin-Key: <key>)
						            .addSecuritySchemes(
								            SCHEME_ADMIN_KEY_HEADER, new SecurityScheme()
										            .type(SecurityScheme.Type.APIKEY)
										            .in(SecurityScheme.In.HEADER)
										            .name("X-Admin-Key")
										            .description("""
												                         Administrative authentication via dedicated request header.
												                         Requires the configured master administrative key.
												                         """)
						            )
						            // 3. Admin Bearer Key Auth (Authorization: Bearer <master-key>)
						            .addSecuritySchemes(
								            SCHEME_ADMIN_BEARER, new SecurityScheme()
										            .type(SecurityScheme.Type.HTTP)
										            .scheme("bearer")
										            .bearerFormat("MasterKey")
										            .description("""
												                         Administrative authentication via standard Bearer header.
												                         Accepts the configured master administrative key.
												                         """)
						            )
				);
	}

	/**
	 * Public AI Gateway endpoints group.
	 *
	 * @return grouped public API specification
	 */
	@Bean
	public GroupedOpenApi publicGatewayApi() {
		return GroupedOpenApi.builder()
		                     .group("1-public-gateway")
		                     .displayName("1. Public Gateway APIs")
		                     .pathsToMatch("/v1/chat/**", "/v1/embeddings/**")
		                     .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(
				                     new SecurityRequirement().addList(SCHEME_BEARER_AUTH)))
		                     .build();
	}

	/**
	 * Administrative Control Plane endpoints group.
	 *
	 * @return grouped admin API specification
	 */
	@Bean
	public GroupedOpenApi adminPortalApi() {
		return GroupedOpenApi.builder()
		                     .group("2-admin-portal")
		                     .displayName("2. Administrative APIs")
		                     .pathsToMatch("/v1/admin/**")
		                     .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(
				                     new SecurityRequirement()
						                     .addList(SCHEME_ADMIN_KEY_HEADER)
						                     .addList(SCHEME_ADMIN_BEARER)))
		                     .build();
	}

	/**
	 * Observability &amp; Actuator Management endpoints group.
	 *
	 * @return grouped observability API specification
	 */
	@Bean
	public GroupedOpenApi actuatorApi() {
		return GroupedOpenApi.builder()
		                     .group("3-observability")
		                     .displayName("3. Observability & Actuator")
		                     .pathsToMatch("/actuator/**")
		                     .build();
	}
}
