package io.github.kxng0109.aegisgate.security.ratelimit;

import com.redis.testcontainers.RedisContainer;
import io.github.kxng0109.aegisgate.contracts.BootstrapKey;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.proxy.failover.FailoverOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test of the Phase 2 security pipeline against a real Redis instance (Testcontainers):
 * virtual-key authentication, the distributed RPM/TPM rate limiter, model allow-list enforcement, and fail-closed
 * behavior when Redis becomes unavailable.
 *
 * <p>The upstream provider is pointed at a non-routable TEST-NET address so
 * that requests which pass authentication never reach an external service: an allowed request fails fast with 502/504,
 * which is the signal that the auth and rate-limit layers admitted it.</p>
 *
 * <p>Redis connection details are injected via {@link DynamicPropertySource}
 * (the canonical Spring testing mechanism) rather than {@code @ServiceConnection}, which in Spring Boot 4.x requires a
 * separate per-database connection-details factory module. Requests are issued with the JDK {@link HttpClient} so the
 * test needs no additional test-client dependency.</p>
 */
@Testcontainers
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"gateway.providers.upstream.name=upstream",
				"gateway.providers.upstream.type=OPENAI",
				"gateway.providers.upstream.base-url=http://203.0.113.1:1",
				"gateway.providers.upstream.api-key=itest-key",
				"gateway.providers.upstream.connect-timeout=500ms",
				"gateway.providers.upstream.request-timeout=2s",
				"gateway.aliases.gpt-4o.chain[0].provider-name=upstream",
				"gateway.aliases.gpt-4o.strategy=SEQUENTIAL",
				"gateway.cache.enabled=false",
				"spring.data.redis.timeout=3s"
		})
@DisplayName("Rate limiting and virtual-key auth against real Redis")
class RateLimitIntegrationTest {

	private static final String PATH = "/v1/chat/completions";

	@Container
	static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

	static {
		// Start synchronously at class-load time so the container is guaranteed
		// to be running before @DynamicPropertySource is evaluated (the JUnit
		// @Testcontainers extension may otherwise start it after the Spring test
		// context has already been created). @Testcontainers still performs the
		// after-suite cleanup.
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@LocalServerPort
	private int port;

	@Autowired
	private KeyManagementService keyManagementService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private FailoverOrchestrator orchestrator;

	private final HttpClient httpClient = HttpClient.newBuilder()
	                                                .connectTimeout(Duration.ofSeconds(3))
	                                                .version(HttpClient.Version.HTTP_2)
	                                                .followRedirects(HttpClient.Redirect.NEVER)
	                                                .build();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void resetRedisState() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		orchestrator.resetCircuitBreakers();
	}

	@Test
	@DisplayName("missing or malformed credentials are rejected with 401")
	void rejectsMissingAndMalformedCredentials() throws Exception {
		HttpResponse<String> missing = post(null, body("gpt-4o", 100));
		assertThat(missing.statusCode()).isEqualTo(401);
		assertThat(errorCode(missing)).isEqualTo("KEY_NOT_FOUND");

		HttpResponse<String> malformed = post("not-a-key", body("gpt-4o", 100));
		assertThat(malformed.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("RPM limit is enforced with X-RateLimit headers and 429")
	void enforcesRpmLimitWithHeaders() throws Exception {
		String key = seedKey("gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2, 100_000, Set.of(), true);

		HttpResponse<String> first = post(key, body("gpt-4o", 100));
		assertThat(first.statusCode()).isIn(502, 504);
		assertThat(first.headers().firstValue("X-RateLimit-Limit-RPM").orElseThrow()).isEqualTo("2");
		assertThat(first.headers().firstValue("X-RateLimit-Remaining-RPM").orElseThrow()).isEqualTo("1");
		assertThat(first.headers().firstValue("X-RateLimit-Limit-TPM").orElseThrow()).isEqualTo("100000");
		assertThat(first.headers().firstValue("X-RateLimit-Remaining-TPM").orElseThrow()).isEqualTo("99900");

		HttpResponse<String> second = post(key, body("gpt-4o", 100));
		assertThat(second.statusCode()).isIn(502, 504);
		assertThat(second.headers().firstValue("X-RateLimit-Remaining-RPM").orElseThrow()).isEqualTo("0");

		HttpResponse<String> third = post(key, body("gpt-4o", 100));
		assertThat(third.statusCode()).isEqualTo(429);
		long retryAfter = Long.parseLong(third.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isGreaterThanOrEqualTo(1);
		assertThat(third.headers().firstValue("X-RateLimit-Remaining-RPM").orElseThrow()).isEqualTo("0");
		assertThat(errorCode(third)).isEqualTo("RPM_EXCEEDED");
	}

	@Test
	@DisplayName("TPM limit is enforced using the pre-flight token estimate")
	void enforcesTpmLimit() throws Exception {
		String key = seedKey("gw-cccccccccccccccccccccccccccccccc", 100_000, 150, Set.of(), true);

		HttpResponse<String> first = post(key, body("gpt-4o", 100));
		assertThat(first.statusCode()).isIn(502, 504);

		HttpResponse<String> second = post(key, body("gpt-4o", 100));
		assertThat(second.statusCode()).isEqualTo(429);
		assertThat(errorCode(second)).isEqualTo("TPM_EXCEEDED");
	}

	@Test
	@DisplayName("disabled keys are rejected with 403")
	void rejectsDisabledKey() throws Exception {
		String key = seedKey("gw-dddddddddddddddddddddddddddddddd", 100_000, 100_000, Set.of(), false);

		HttpResponse<String> response = post(key, body("gpt-4o", 100));
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(errorCode(response)).isEqualTo("KEY_DISABLED");
	}

	@Test
	@DisplayName("model allow-list is enforced before proxying")
	void enforcesModelAllowList() throws Exception {
		String key = seedKey("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", 100_000, 100_000, Set.of("gpt-4o"), true);

		HttpResponse<String> blocked = post(key, body("gpt-3.5-turbo", 100));
		assertThat(blocked.statusCode()).isEqualTo(403);
		assertThat(errorCode(blocked)).isEqualTo("MODEL_NOT_ALLOWED");

		HttpResponse<String> allowed = post(key, body("gpt-4o", 100));
		assertThat(allowed.statusCode()).isIn(502, 504);
	}

	@Test
	@DisplayName("the gateway fails closed (503) while Redis is unavailable and recovers afterwards")
	void failsClosedWhenRedisUnavailable() throws Exception {
		String key = seedKey("gw-ffffffffffffffffffffffffffffffff", 100_000, 100_000, Set.of(), true);

		HttpResponse<String> before = post(key, body("gpt-4o", 100));
		assertThat(before.statusCode()).isIn(502, 504);

		// Pause (not stop) the container: pausing keeps the mapped host port
		// stable, which the already-created Spring context still points at.
		// New connections are accepted but never answered, so the rate-limit
		// engine surfaces a DataAccessException and fails closed with 503.
		try {
			REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
			HttpResponse<String> during = post(key, body("gpt-4o", 100));
			assertThat(during.statusCode()).isEqualTo(503);
		} finally {
			REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
		}

		HttpResponse<String> after = post(key, body("gpt-4o", 100));
		assertThat(after.statusCode()).isIn(502, 504);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private String seedKey(String plaintext, int rpm, int tpm, Set<String> models, boolean enabled) {
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(new BootstrapKey(
				"it-owner", "integration-test", plaintext, rpm, tpm, models, Set.of())));
		keyManagementService.seedBootstrapKeys(properties);
		if (!enabled) {
			keyManagementService.revokeKey(SHA256Hash.fromRawKey(plaintext));
		}
		return plaintext;
	}

	private HttpResponse<String> post(String bearerKey, String jsonBody) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
		                                         .timeout(Duration.ofSeconds(10))
		                                         .header("Content-Type", "application/json")
		                                         .POST(HttpRequest.BodyPublishers.ofString(
				                                         jsonBody,
				                                         StandardCharsets.UTF_8
		                                         ));
		if (bearerKey != null) {
			builder.header("Authorization", "Bearer " + bearerKey);
		}
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String errorCode(HttpResponse<String> response) {
		try {
			JsonNode root = objectMapper.readTree(response.body());
			return root.path("error").path("code").asString();
		} catch (JacksonException e) {
			throw new IllegalStateException("Failed to parse error response", e);
		}
	}

	private static String body(String model, int maxTokens) {
		return "{\"model\":\"" + model + "\","
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
				+ "\"max_tokens\":" + maxTokens + "}";
	}
}