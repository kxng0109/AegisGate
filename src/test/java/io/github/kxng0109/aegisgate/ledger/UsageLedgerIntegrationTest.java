package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the ledger and pricing layers against a real PostgreSQL instance (Testcontainers, wired through
 * {@link ServiceConnection}): the retrying migrator applies the Flyway schema, a published usage event lands in the
 * ledger, duplicate request ids cannot create duplicate rows, the cost calculator reads the seeded pricing table, and a
 * catalog refresh upserts new rows. The pricing catalog URL is pointed at an in process server so the startup sync
 * never touches the network.</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Usage ledger and pricing against real PostgreSQL")
class UsageLedgerIntegrationTest {

	private static final String PRICING_CATALOG = """
			{
			  "gpt-5.5": {
			    "input_cost_per_token": 2e-06,
			    "output_cost_per_token": 8e-06,
			    "litellm_provider": "openai",
			    "mode": "chat"
			  }
			}
			""";

	private static final MockWebServer PRICING_SERVER = new MockWebServer();

	static {
		try {
			PRICING_SERVER.start();
			PRICING_SERVER.enqueue(new MockResponse().setResponseCode(200).setBody(PRICING_CATALOG));
		} catch (Exception ex) {
			throw new IllegalStateException("could not start the pricing mock server", ex);
		}
	}

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void pricingSource(DynamicPropertyRegistry registry) {
		registry.add("gateway.pricing.source-url", () -> PRICING_SERVER.url("/prices.json").toString());
	}

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private UsageLedgerRepository usageLedgerRepository;

	@Autowired
	private ModelPriceCatalog priceCatalog;

	@Autowired
	private CostCalculator costCalculator;

	@Autowired
	private PricingSyncService pricingSyncService;

	@Test
	@DisplayName("a published usage event is persisted asynchronously")
	void usageEventIsPersisted() {
		UUID requestId = UUID.randomUUID();
		TokenUsageEvent event = new TokenUsageEvent(
				requestId, "owner-1", "openai", "gpt-5.6-sol",
				1000, 500, 1500, 250, 14_000, Instant.now()
		);

		eventPublisher.publishEvent(event);

		awaitEntry(requestId);
		UsageLedgerEntry entry = usageLedgerRepository.findByRequestId(requestId).orElseThrow();
		assertThat(entry.getOwnerId()).isEqualTo("owner-1");
		assertThat(entry.getProvider()).isEqualTo("openai");
		assertThat(entry.getModel()).isEqualTo("gpt-5.6-sol");
		assertThat(entry.getPromptTokens()).isEqualTo(1000);
		assertThat(entry.getCompletionTokens()).isEqualTo(500);
		assertThat(entry.getTotalTokens()).isEqualTo(1500);
		assertThat(entry.getCostUsdMicros()).isEqualTo(14_000);
		assertThat(entry.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("a duplicate request id cannot create a second row")
	void duplicateRequestIdIsIdempotent() {
		UUID requestId = UUID.randomUUID();
		TokenUsageEvent event = new TokenUsageEvent(
				requestId, "owner-1", "openai", "gpt-5.6-sol",
				10, 5, 15, 50, 140, Instant.now()
		);

		eventPublisher.publishEvent(event);
		awaitEntry(requestId);
		eventPublisher.publishEvent(event);

		assertThat(usageLedgerRepository.findAll().stream()
		                                .filter(entry -> entry.getRequestId().equals(requestId)).count())
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the cost calculator reads the seeded pricing table")
	void costCalculatorReadsSeeds() {
		long micros = costCalculator.calculate(ProviderType.OPENAI, "gpt-5.6-sol", 1000, 500);
		assertThat(micros).isEqualTo(14_000);
		assertThat(priceCatalog.lookup(ProviderType.ANTHROPIC, "claude-sonnet-5")).isPresent();
		assertThat(priceCatalog.lookup(ProviderType.OLLAMA, "llama3.2")).isPresent();
	}

	@Test
	@DisplayName("a catalog refresh upserts new pricing rows")
	void refreshUpsertsNewRows() {
		PRICING_SERVER.enqueue(new MockResponse().setResponseCode(200).setBody(PRICING_CATALOG));
		pricingSyncService.refresh();

		assertThat(priceCatalog.lookup(ProviderType.OPENAI, "gpt-5.5")).isPresent();
	}

	private void awaitEntry(UUID requestId) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			Optional<UsageLedgerEntry> entry = usageLedgerRepository.findByRequestId(requestId);
			if (entry.isPresent()) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while awaiting the ledger row", ex);
			}
		}
		throw new IllegalStateException("the usage event was not persisted within 10 seconds");
	}
}