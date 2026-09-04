package io.github.kxng0109.aegisgate.mcp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;

/**
 * High-performance HTTP/2 connection pooling configuration for downstream MCP server dispatch.
 */
@Configuration
@RequiredArgsConstructor
public class McpHttpClientConfig {

	private final McpGatewayProperties properties;

	/**
	 * Shared HTTP/2 client for all upstream MCP communication. Redirects are never followed (SSRF defense), and virtual
	 * threads carry the I/O.
	 */
	@Bean("mcpHttpClient")
	public HttpClient mcpHttpClient() {
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .connectTimeout(properties.getClientConnectTimeout())
		                 .executor(Executors.newVirtualThreadPerTaskExecutor())
		                 .build();
	}
}
