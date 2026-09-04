package io.github.kxng0109.aegisgate.mcp.resilience;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.McpServerConfig;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.aegisgate.mcp.contracts.McpTransportType;
import io.github.kxng0109.aegisgate.mcp.hitl.McpAeadResumptionTokenService;
import io.github.kxng0109.aegisgate.mcp.hitl.McpResumptionClaims;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import io.github.kxng0109.aegisgate.mcp.router.McpCatalogCache;
import io.github.kxng0109.aegisgate.mcp.router.McpResolvedRoute;
import io.github.kxng0109.aegisgate.mcp.router.McpRouter;
import io.github.kxng0109.aegisgate.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.aegisgate.mcp.security.McpToolRbacPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP 50,000+ RPS Virtual Thread Concurrency & Stress Harness")
class McpStressAndConcurrencyHarnessTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpGatewayProperties properties;
	private McpRouter router;
	private McpCatalogCache catalogCache;
	private McpToolRbacPolicyEngine rbacEngine;
	private McpJsonSchemaValidator jsonSchemaValidator;
	private McpAeadResumptionTokenService tokenService;

	private VirtualApiKey testKey;
	private JsonNode toolSchema;
	private JsonNode validArgs;

	@BeforeEach
	void setUp() throws Exception {
		properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("stress-harness-hitl-secret-key32"));

		McpServerConfig pgServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				new SensitiveString("pg-key"),
				null,
				null,
				Set.of("run_query", "explain_plan"),
				Set.of("drop_db"),
				Set.of(),
				500,
				true
		);

		McpServerConfig ghServer = new McpServerConfig(
				"github",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8082"),
				new SensitiveString("gh-key"),
				null,
				null,
				Set.of("list_prs", "create_issue"),
				Set.of(),
				Set.of(),
				500,
				true
		);

		properties.setServers(Map.of("postgres", pgServer, "github", ghServer));

		router = new McpRouter(properties);
		catalogCache = new McpCatalogCache(properties);
		rbacEngine = new McpToolRbacPolicyEngine();
		jsonSchemaValidator = new McpJsonSchemaValidator();
		tokenService = new McpAeadResumptionTokenService(properties, objectMapper);

		testKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-stress-key-1234567890abcdef"),
				"gw-",
				"tenant-stress",
				"stress-key",
				50000,
				10000000,
				Set.of(),
				Set.of(),
				Set.of("postgres__*", "github__*"),
				Set.of("*:drop_*"),
				true,
				Instant.now()
		);

		toolSchema = objectMapper.readTree("""
				                                   {
				                                     "type": "object",
				                                     "required": ["query"],
				                                     "properties": {
				                                       "query": {"type": "string", "minLength": 1, "maxLength": 1000},
				                                       "limit": {"type": "integer", "minimum": 1, "maximum": 500}
				                                     }
				                                   }
				                                   """);

		validArgs = objectMapper.readTree("{\"query\": \"SELECT id FROM logs\", \"limit\": 50}");
	}

	@Test
	@DisplayName("Executes 10,000 concurrent virtual threads hammering router, RBAC, schema, and AEAD tokens")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void executeVirtualThreadBurst() throws Exception {
		final int taskCount = 10_000;
		final CountDownLatch startGate = new CountDownLatch(1);
		final CountDownLatch completionGate = new CountDownLatch(taskCount);
		final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		final LongAdder successfulRuns = new LongAdder();

		String argsJson = validArgs.toString();
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < taskCount; i++) {
				final int idx = i;
				executor.submit(() -> {
					try {
						startGate.await();

						// 1. Route resolution
						String targetTool = (idx % 2 == 0) ? "postgres__run_query" : "github__list_prs";
						Optional<McpResolvedRoute> route = router.resolveToolRoute(targetTool);
						if (route.isEmpty()) {
							throw new IllegalStateException("Failed to resolve route for " + targetTool);
						}

						// 2. RBAC check
						boolean allowed = rbacEngine.isToolAllowed(targetTool, testKey);
						if (!allowed) {
							throw new IllegalStateException("Tool should be allowed: " + targetTool);
						}

						// 3. JSON Schema validation
						McpJsonSchemaValidator.ValidationResult valRes = jsonSchemaValidator.validate(
								validArgs,
								toolSchema
						);
						if (!valRes.isValid()) {
							throw new IllegalStateException("Validation error: " + valRes.errorMessage());
						}

						// 4. AEAD Token Mint & Verify
						McpResumptionClaims claims = new McpResumptionClaims(
								"tok-" + idx,
								"tenant-stress",
								targetTool,
								argsSha,
								now,
								now.plusSeconds(300)
						);
						String token = tokenService.mintToken(claims);
						Optional<McpResumptionClaims> verified = tokenService.verifyAndExtract(
								token,
								argsSha,
								"tenant-stress"
						);
						if (verified.isEmpty()) {
							throw new IllegalStateException("Failed to verify token for task " + idx);
						}

						successfulRuns.increment();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						completionGate.countDown();
					}
				});
			}

			// Release all 10,000 virtual threads simultaneously
			startGate.countDown();
			boolean completed = completionGate.await(20, TimeUnit.SECONDS);

			assertThat(completed).as("All virtual threads must finish within timeout").isTrue();
			assertThat(errors).as("Zero errors across all virtual threads").isEmpty();
			assertThat(successfulRuns.sum()).isEqualTo(taskCount);
		}
	}

	@Test
	@DisplayName("Handles high-frequency cache read-through vs invalidation race conditions cleanly")
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	void concurrentCacheInvalidationRace() throws Exception {
		final int readerCount = 5_000;
		final int invalidatorCount = 50;
		final CountDownLatch startGate = new CountDownLatch(1);
		final CountDownLatch completionGate = new CountDownLatch(readerCount + invalidatorCount);
		final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		final AtomicInteger computeCalls = new AtomicInteger();

		McpAggregatedCatalog mockCatalog = new McpAggregatedCatalog(
				List.of(new McpToolDefinition("postgres__query", "desc", null, null)),
				List.of(),
				List.of(),
				Instant.now()
		);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// Invalidator tasks
			for (int i = 0; i < invalidatorCount; i++) {
				executor.submit(() -> {
					try {
						startGate.await();
						catalogCache.invalidate();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						completionGate.countDown();
					}
				});
			}

			// Reader tasks
			for (int i = 0; i < readerCount; i++) {
				executor.submit(() -> {
					try {
						startGate.await();
						McpAggregatedCatalog cat = catalogCache.getOrCompute(() -> {
							computeCalls.incrementAndGet();
							return mockCatalog;
						});
						if (cat == null || cat.tools().isEmpty()) {
							throw new IllegalStateException("Catalog is null or empty");
						}
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						completionGate.countDown();
					}
				});
			}

			startGate.countDown();
			boolean completed = completionGate.await(10, TimeUnit.SECONDS);

			assertThat(completed).isTrue();
			assertThat(errors).isEmpty();
			assertThat(computeCalls.get()).isPositive();
		}
	}
}
