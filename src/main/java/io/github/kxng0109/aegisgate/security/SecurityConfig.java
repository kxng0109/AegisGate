package io.github.kxng0109.aegisgate.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Fail-closed authorization boundary for the gateway.
 *
 * <p>Prior to this config the gateway had no Spring Security on the classpath and
 * relied entirely on {@code FilterRegistrationBean}-registered {@code OncePerRequestFilter} instances. Every one of
 * those filters pinned its URL pattern to {@code /v1/chat/completions}, so {@code POST /v1/embeddings} (and any future
 * route) executed with <b>no authentication, no rate limit, and no budget gate</b> before reaching the upstream
 * provider. There was no framework-enforced invariant that a route must be covered by a gate; coverage was only as good
 * as the most recently updated {@code shouldNotFilter()}.
 *
 * <h2>Design</h2>
 * <p>This config inverts the burden: <b>deny by default, permit explicitly</b>. A
 * request is allowed only when it matches a declared matcher; every other route receives HTTP 403 before any controller
 * or filter runs. Adding a new route can never silently bypass the boundary  -  it is denied until someone declares
 * it.
 *
 * <h2>Filter ordering (verified)</h2>
 * <p>Spring Boot auto-configures the security chain at order
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}, which runs
 * <em>before</em> the gateway's own filters at orders 0/1/2. The routes whose real
 * authentication lives in those filters are therefore {@code permitAll}ed here so the security chain passes them
 * through and the delegated filter still executes. The security chain never replaces the delegated filters; it is the
 * backstop.
 *
 * <h2>Permitted routes</h2>
 * <ul>
 *   <li><b>Public observability/docs:</b> {@code /actuator/health},
 *       {@code /actuator/prometheus}, {@code /v3/api-docs}, {@code /swagger-ui.html}
 *       (no secrets; load-balancer and scrape access only).</li>
 *   <li><b>Delegated auth:</b> {@code /v1/chat/completions} and
 *       {@code /v1/embeddings} (authenticated, rate-limited, and budget-gated by
 *       {@code KeyAuthFilter}); {@code /v1/admin/**} (master-key authenticated by
 *       {@code AdminAuthFilter}).</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 * <ul>
 *   <li>CSRF disabled  -  the API is stateless bearer-token; CSRF tokens have no
 *       meaning and would only break legitimate clients.</li>
 *   <li>Session creation forced to {@code STATELESS}  -  no session state is held,
 *       so there is no session to hijack or fixate.</li>
 *   <li>Default-deny is fail-closed: an unmatched route is refused, never passed
 *       through.</li>
 * </ul>
 *
 * @since 1.8.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * Single fail-closed filter chain. {@code anyRequest().denyAll()} is the terminal rule: every route not listed
	 * above is refused before reaching any controller.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(
								"/actuator/health",
								"/actuator/prometheus",
								"/v3/api-docs",
								"/swagger-ui.html"
						).permitAll()
						.requestMatchers(
								"/v1/chat/completions",
								"/v1/embeddings",
								"/v1/admin/**"
						).permitAll()
						.anyRequest().denyAll()
				)
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}
}