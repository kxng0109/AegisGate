package io.github.kxng0109.aegisgate.proxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Configuration for the shared upstream {@link HttpClient} bean.
 *
 * <p>One client serves every provider so TCP connections and HTTP/2 sessions
 * are pooled and reused. Redirects are never followed, which is a core SSRF control. Virtual threads carry the
 * asynchronous work.</p>
 *
 * <p>The connect timeout is a fixed conservative default because the JDK
 * client applies connect timeouts per client rather than per request. Per provider responsiveness is enforced per
 * request through {@code HttpRequest.Builder.timeout}, which bounds the time to the first byte including the connection
 * establishment.</p>
 */
@Configuration
public class HttpClientConfig {

	/**
	 * Default bound for establishing a connection, five seconds.
	 */
	static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Creates the shared proxy HTTP client bean.
	 *
	 * @return the shared client
	 */
	@Primary
	@Bean("proxyHttpClient")
	public HttpClient proxyHttpClient() {
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
		                 .executor(Executors.newVirtualThreadPerTaskExecutor())
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .build();
	}
}