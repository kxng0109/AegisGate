package io.github.kxng0109.aegisgate.proxy.config;

import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;

/**
 * Configuration for the shared upstream {@link HttpClient} bean.
 *
 * <p>Configures the client with HTTP/2, virtual-thread executor for non-blocking I/O,
 * connect timeout from configuration, and redirect disabling for SSRF defense.
 */
@Configuration
public class HttpClientConfig {

	/**
	 * Creates the shared proxy HTTP client bean.
	 *
	 * @param upstreamConfig upstream configuration providing connect timeout
	 * @return configured HTTP client instance
	 */
	@Bean
	public HttpClient proxyHttpClient(UpstreamConfig upstreamConfig) {
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .connectTimeout(upstreamConfig.connectTimeout())
		                 .executor(Executors.newVirtualThreadPerTaskExecutor())
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .build();
	}
}