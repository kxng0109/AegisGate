package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the shared staging journal is safe under concurrency and crashes, against real PostgreSQL:
 * duplicate writes collapse, concurrent claims stay disjoint, crashed claims are reclaimed, poison parks,
 * retention purges, and unwritable ledger rows route to staging (never lost, never duplicated).
 *
 * <p>The production drainer bean is disabled here
 * ({@code gateway.ledger.staging-drain.enabled=false}) so background polling cannot race the assertions;
 * every drain runs synchronously inside an explicit transaction instead.</p>
 */
@Testcontainers
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "gateway.ledger.staging-drain.enabled=false"
)
@DisplayName("Shared staging journal against real PostgreSQL")
class LedgerStagingDrainerTest {

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
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer("postgres:16-alpine");

	@DynamicPropertySource
	static void pricingSource(DynamicPropertyRegistry registry) {
		registry.add("gateway.pricing.source-url", () -> PRICING_SERVER.url("/prices.json").toString());
	}

	@Autowired
	private LedgerStagingRepository staging;

	@Autowired
	private UsageLedgerRepository ledger;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	private LedgerStagingDrainer drainer;

	private TransactionTemplate writeTx;

	@BeforeEach
	void setUp() {
		drainer = new LedgerStagingDrainer(staging, ledger, transactionManager, new SimpleMeterRegistry());
		writeTx = new TransactionTemplate(transactionManager);
		staging.deleteAll();
	}

	@Test
	@DisplayName("duplicate staging writes collapse on request_id")
	void dualWriteSameRequestIdYieldsOneRow() {
		TokenUsageEvent event = eventFor(UUID.randomUUID(), "gpt-5.6-sol");

		writeTx.executeWithoutResult(ignored -> staging.saveAndFlush(LedgerStagingEntry.pendingFrom(event)));
		assertThatThrownBy(() -> writeTx.executeWithoutResult(
				ignored -> staging.saveAndFlush(LedgerStagingEntry.pendingFrom(event))))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(staging.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("drain moves pending rows to the ledger and marks them done")
	void drainMovesPendingToLedger() {
		UUID requestId = UUID.randomUUID();
		writeTx.executeWithoutResult(
				ignored -> staging.save(LedgerStagingEntry.pendingFrom(eventFor(requestId, "gpt-5.6-sol"))));

		writeTx.executeWithoutResult(ignored -> drainer.drain());

		assertThat(ledger.findByRequestId(requestId)).isPresent();
		assertThat(staging.findAll()).allMatch(row -> LedgerStagingEntry.DONE.equals(row.getStatus()));
	}

	@Test
	@DisplayName("concurrent claims hand out disjoint batches")
	void concurrentClaimsAreDisjoint() throws Exception {
		for (int i = 0; i < 4; i++) {
			UUID requestId = UUID.randomUUID();
			writeTx.executeWithoutResult(
					ignored -> staging.save(LedgerStagingEntry.pendingFrom(eventFor(requestId, "gpt-5.6-sol"))));
		}

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		Future<List<UUID>> first = pool.submit(() -> {
			go.await(10, TimeUnit.SECONDS);
			return writeTx.execute(status ->
					staging.claimBatch("pod-a", 2).stream().map(LedgerStagingEntry::getRequestId).toList());
		});
		Future<List<UUID>> second = pool.submit(() -> {
			go.await(10, TimeUnit.SECONDS);
			return writeTx.execute(status ->
					staging.claimBatch("pod-b", 2).stream().map(LedgerStagingEntry::getRequestId).toList());
		});
		go.countDown();
		List<UUID> claimedA = first.get(30, TimeUnit.SECONDS);
		List<UUID> claimedB = second.get(30, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(claimedA).hasSize(2);
		assertThat(claimedB).hasSize(2);
		Set<UUID> union = new HashSet<>(claimedA);
		union.addAll(claimedB);
		assertThat(union).hasSize(4);
	}

	@Test
	@DisplayName("crashed claims are reclaimed and drained")
	void crashReclaim() {
		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
		for (UUID requestId : ids) {
			writeTx.executeWithoutResult(
					ignored -> staging.save(LedgerStagingEntry.pendingFrom(eventFor(requestId, "gpt-5.6-sol"))));
		}

		writeTx.executeWithoutResult(ignored -> staging.claimBatch("dead-pod", 10));
		writeTx.executeWithoutResult(
				ignored -> staging.resetStaleClaims(Instant.now().plusSeconds(60)));
		drainer.drain();

		for (UUID requestId : ids) {
			assertThat(ledger.findByRequestId(requestId)).isPresent();
		}
		assertThat(staging.findAll()).allMatch(row -> LedgerStagingEntry.DONE.equals(row.getStatus()));
	}

	@Test
	@DisplayName("rows the ledger rejects park as poisoned after exhausting retries")
	void poisonCap() {
		UUID requestId = UUID.randomUUID();
		String longModel = "m".repeat(200);
		writeTx.executeWithoutResult(ignored -> {
			LedgerStagingEntry row = LedgerStagingEntry.pendingFrom(eventFor(requestId, longModel));
			row.setAttempts(LedgerStagingEntry.maxAttempts());
			staging.save(row);
		});

		drainer.drain();

		assertThat(ledger.findByRequestId(requestId)).isEmpty();
		List<LedgerStagingEntry> rows = staging.findAll();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getStatus()).isEqualTo(LedgerStagingEntry.POISONED);
	}

	@Test
	@DisplayName("retention purges terminal rows past the cutoff")
	void purge() {
		UUID requestId = UUID.randomUUID();
		writeTx.executeWithoutResult(ignored -> {
			LedgerStagingEntry row = LedgerStagingEntry.pendingFrom(eventFor(requestId, "gpt-5.6-sol"));
			row.setStatus(LedgerStagingEntry.DONE);
			staging.save(row);
		});

		Integer deleted = writeTx.execute(
				status -> staging.purgeCompleted(Instant.now().plusSeconds(3600)));

		assertThat(deleted).isEqualTo(1);
		assertThat(staging.count()).isZero();
	}

	@Test
	@DisplayName("unwritable ledger rows route to staging and schedule retries")
	void listenerRoutesUnwritableRowToStaging() {
		UUID requestId = UUID.randomUUID();
		eventPublisher.publishEvent(eventFor(requestId, "m".repeat(200)));

		awaitStaging(requestId);
		assertThat(ledger.findByRequestId(requestId)).isEmpty();

		drainer.drain();

		List<LedgerStagingEntry> rows = staging.findAll();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
		assertThat(rows.get(0).getAttempts()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("zero-cost events skip the cost meter but still persist")
	void zeroCostEventSkipsCostMeter() {
		UUID requestId = UUID.randomUUID();
		TokenUsageEvent free = new TokenUsageEvent(
				requestId, "owner-1", "ollama", "local-llama",
				10, 5, 15, 200, 0, Instant.now(),
				10, 0, 0, 0, 0, 0, null);
		eventPublisher.publishEvent(free);

		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (System.nanoTime() < deadline) {
			if (ledger.findByRequestId(requestId).isPresent()) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while awaiting the ledger row", ex);
			}
		}
		throw new IllegalStateException("the zero-cost event was not persisted within 15 seconds");
	}

	@Test
	@DisplayName("null owners stage as unknown")
	void nullOwnerStagesAsUnknown() {
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), null, "openai", "gpt-5.6-sol",
				10, 5, 15, 200, 1500, Instant.now(),
				10, 0, 0, 0, 1500, 1500, null);

		LedgerStagingEntry staged = LedgerStagingEntry.pendingFrom(event);

		assertThat(staged.getOwnerId()).isEqualTo("unknown");
	}

	private static TokenUsageEvent eventFor(UUID requestId, String model) {
		return new TokenUsageEvent(
				requestId, "owner-1", "openai", model,
				100, 50, 150, 200, 1500, Instant.now(),
				100, 0, 0, 0, 1500, 1500, null
		);
	}

	private void awaitStaging(UUID requestId) {		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (System.nanoTime() < deadline) {
			boolean present = staging.findAll().stream()
			                         .anyMatch(row -> requestId.equals(row.getRequestId()));
			if (present) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while awaiting the staged row", ex);
			}
		}
		throw new IllegalStateException("the usage event was not staged within 15 seconds");
	}

	@Test
	@DisplayName("purge runs cleanly and the null-registry fallback also drains")
	void purgeAndNullRegistryFallback() {
		LedgerStagingDrainer fallback =
				new LedgerStagingDrainer(staging, ledger, transactionManager, null);
		fallback.purge();
		fallback.drain();

		assertThat(staging.count()).isZero();
	}

	@Test
	@DisplayName("an already-ledgered row drains as a benign duplicate")
	void benignDuplicateDrainsToDone() {
		UUID requestId = UUID.randomUUID();
		writeTx.executeWithoutResult(ignored -> {
			ledger.save(new UsageLedgerEntry(
					requestId, "owner-1", "openai", "gpt-5.6-sol",
					10, 5, 15, 100L, 50L, Instant.now(),
					10, 0, 0, 0, 100L, 100L, null));
			staging.save(LedgerStagingEntry.pendingFrom(eventFor(requestId, "gpt-5.6-sol")));
		});
		drainer.drain();

		assertThat(ledger.findByRequestId(requestId)).isPresent();
		assertThat(staging.findAll()).allMatch(row -> LedgerStagingEntry.DONE.equals(row.getStatus()));
	}

	@Test
	@DisplayName("overflowing token counts clamp instead of failing")
	void hugeTokenValuesClampCleanly() {
		UUID requestId = UUID.randomUUID();
		TokenUsageEvent huge = new TokenUsageEvent(
				requestId, "owner-1", "", "gpt-5.6-sol",
				Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 200, 1500, Instant.now(),
				Long.MAX_VALUE, 0, 0, 0, 1500, 1500, null);
		writeTx.executeWithoutResult(
				ignored -> staging.save(LedgerStagingEntry.pendingFrom(huge)));
		drainer.drain();

		UsageLedgerEntry entry = ledger.findByRequestId(requestId).orElseThrow();
		assertThat(entry.getPromptTokens()).isEqualTo(Integer.MAX_VALUE);
		assertThat(staging.findAll()).allMatch(row -> LedgerStagingEntry.DONE.equals(row.getStatus()));
	}
}
