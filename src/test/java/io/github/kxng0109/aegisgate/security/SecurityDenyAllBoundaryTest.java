package io.github.kxng0109.aegisgate.security;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Proves the fail-closed default-deny boundary end-to-end against a live server.
 *
 * <p>PRIOR to the security config, every route that was not explicitly registered
 * with a {@code FilterRegistrationBean} executed with no gate at all  -  a newly added controller could silently bypass
 * authentication, rate limiting, and budget enforcement. This class locks that down: an unmatched route is refused, and
 * the routes whose real auth is delegated to {@code KeyAuthFilter} / {@code AdminAuthFilter} are passed through (they
 * reject the request themselves when no key is present).
 *
 * <p>Uses the repo's established {@code SpringBootTest + LocalServerPort} pattern
 * with JDK {@link HttpClient}, rather than a WebMvcTest slice: the slice would try to wire every controller without its
 * service dependencies. The full context is the honest test of a boundary that lives in the servlet filter chain.
 */
@DisplayName("Fail-closed default-deny boundary")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityDenyAllBoundaryTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	@Container
	@ServiceConnection
	static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.8.2-alpine3.23"));

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("public observability routes are permitted")
	void publicRoutesPermitted() throws Exception {
		// Aggregate health is a composite of indicators (db, redis, diskSpace, ...):
		// a cold runner can report transient 503 until Hikari/Lettuce warm up, so
		// this endpoint alone waits for UP instead of asserting a single shot.
		awaitPublicRouteUp("/actuator/health", Duration.ofSeconds(30), Duration.ofMillis(500));
		assertThat(status("GET", "/v3/api-docs")).isEqualTo(200);
	}

	@Test
	@DisplayName("delegated-auth routes pass through to the controller layer")
	void delegatedAuthRoutesPassThrough() throws Exception {
		// No key present: KeyAuthFilter rejects with 401, so the request reaches
		// the controller layer rather than being refused by the boundary itself.
		assertThat(status("POST", "/v1/chat/completions")).isBetween(400, 499);
		assertThat(status("POST", "/v1/embeddings")).isBetween(400, 499);
	}

	@Test
	@DisplayName("unmatched routes are refused with 403")
	void unmatchedRoutesRefused() throws Exception {
		// 403 when no master key is configured (test default), 401 otherwise —
		// either way the boundary holds and AdminAuthFilter decides.
		assertThat(status("GET", "/v1/admin/anything")).isBetween(400, 499);
		assertThat(status("GET", "/v1/unknown-route")).isEqualTo(403);
		assertThat(status("POST", "/v1/chat/completions/extra")).isEqualTo(403);
	}

	@Test
	@DisplayName("a brand-new route cannot silently bypass the boundary")
	void newRouteCannotBypass() throws Exception {
		// The whole point: adding a controller for /v1/brand-new does not grant it
		// access until someone explicitly permits it.
		assertThat(status("GET", "/v1/brand-new")).isEqualTo(403);
	}




	private int status(String method, String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create("http://localhost:" + port + path))
		                                 .method(method, HttpRequest.BodyPublishers.noBody())
		                                 .build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/**
	 * Polls a public route until it reports {@code 200 OK} or the deadline expires.
	 *
	 * <p>Fail-closed by construction: only HTTP {@code 503} (transient DOWN indicator
	 * while connection pools warm up) and connection-level failures are retried. Any
	 * other status — {@code 401}, {@code 403}, {@code 404}, or anything unexpected —
	 * fails immediately, so a genuine boundary regression can never be masked by the
	 * wait loop.
	 *
	 * @param path public path to poll; must not be {@code null}
	 * @param timeout maximum time to wait for {@code 200 OK}; must be positive
	 * @param interval delay between attempts; must be positive
	 */
	private void awaitPublicRouteUp(String path, Duration timeout, Duration interval) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		Instant deadline = Instant.now().plus(timeout);
		int lastStatus = -1;
		while (true) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
				                                 .uri(URI.create("http://localhost:" + port + path))
				                                 .GET()
				                                 .build();
				lastStatus = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
				if (lastStatus == 200) {
					return;
				}
				assertThat(lastStatus).as("public route %s must never be denied, got %s", path, lastStatus)
				                      .isEqualTo(503);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted while waiting for public route " + path, e);
			} catch (IOException e) {
				lastStatus = -1;
			}
			if (!Instant.now().isBefore(deadline)) {
				break;
			}
			Thread.sleep(interval.toMillis());
		}
		fail("public route " + path + " did not report 200 within " + timeout + ", last status=" + lastStatus);
	}
}






