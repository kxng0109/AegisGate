package io.github.kxng0109.aegisgate.mcp.config;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.contracts.McpProtocolVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Gateway Properties & Config Unit Tests")
class McpGatewayPropertiesTest {

	@Test
	@DisplayName("McpGatewayProperties default values and setters operate cleanly")
	void mcpGatewayPropertiesDefaults() {
		McpGatewayProperties props = new McpGatewayProperties();

		assertThat(props.isEnabled()).isTrue();
		assertThat(props.getDefaultProtocolVersion()).isEqualTo(McpProtocolVersion.LATEST);
		assertThat(props.getCatalogCacheTtl()).isEqualTo(Duration.ofMinutes(5));
		assertThat(props.getCatalogRefreshCron()).isEqualTo("0 */5 * * * *");
		assertThat(props.getHitlSuspensionTtl()).isEqualTo(Duration.ofSeconds(300));
		assertThat(props.getHitlSecret().value()).isNotBlank();
		assertThat(props.getMaxSseMessageBytes()).isEqualTo(2 * 1024 * 1024);
		assertThat(props.isAllowLegacySse()).isTrue();
		assertThat(props.getCircuitBreakerFailureThreshold()).isEqualTo(3);
		assertThat(props.getCircuitBreakerCooldown()).isEqualTo(Duration.ofSeconds(30));
		assertThat(props.getClientConnectTimeout()).isEqualTo(Duration.ofSeconds(5));

		props.setEnabled(false);
		props.setDefaultProtocolVersion("2025-11-25");
		props.setCatalogCacheTtl(Duration.ofMinutes(10));
		props.setCatalogRefreshCron("0 0 * * * *");
		props.setHitlSuspensionTtl(Duration.ofSeconds(600));
		props.setHitlSecret(new SensitiveString("new-secret"));
		props.setMaxSseMessageBytes(4 * 1024 * 1024);
		props.setAllowLegacySse(false);
		props.setCircuitBreakerFailureThreshold(5);
		props.setCircuitBreakerCooldown(Duration.ofSeconds(60));
		props.setClientConnectTimeout(Duration.ofSeconds(10));
		props.setServers(null);

		assertThat(props.isEnabled()).isFalse();
		assertThat(props.getDefaultProtocolVersion()).isEqualTo("2025-11-25");
		assertThat(props.getCatalogCacheTtl()).isEqualTo(Duration.ofMinutes(10));
		assertThat(props.getCatalogRefreshCron()).isEqualTo("0 0 * * * *");
		assertThat(props.getHitlSuspensionTtl()).isEqualTo(Duration.ofSeconds(600));
		assertThat(props.getHitlSecret().value()).isEqualTo("new-secret");
		assertThat(props.getMaxSseMessageBytes()).isEqualTo(4 * 1024 * 1024);
		assertThat(props.isAllowLegacySse()).isFalse();
		assertThat(props.getCircuitBreakerFailureThreshold()).isEqualTo(5);
		assertThat(props.getCircuitBreakerCooldown()).isEqualTo(Duration.ofSeconds(60));
		assertThat(props.getClientConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(props.getServers()).isEmpty();
	}

	@Test
	@DisplayName("McpHttpClientConfig builds valid HTTP/2 client bean")
	void mcpHttpClientConfig() {
		McpGatewayProperties props = new McpGatewayProperties();
		McpHttpClientConfig config = new McpHttpClientConfig(props);

		var client = config.mcpHttpClient();
		assertThat(client).isNotNull();
		assertThat(client.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
		assertThat(client.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
	}
}
