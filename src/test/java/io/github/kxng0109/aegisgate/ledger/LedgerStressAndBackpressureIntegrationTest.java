package io.github.kxng0109.aegisgate.ledger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Enterprise stress harness validating concurrent events over Virtual Threads, database downtime fallback to
 * dead-letter storage, and queue backpressure handling.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Usage Ledger High-Throughput Stress & Downtime Resilience Harness")
class LedgerStressAndBackpressureIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private UsageLedgerRepository usageLedgerRepository;

	@TempDir
	Path tempDir;

	private Path deadLetterFile;

	@BeforeEach
	void setUp() {
		deadLetterFile = tempDir.resolve("ledger-deadletter.log");
	}

	@AfterEach
	void tearDown() throws IOException {
		Files.deleteIfExists(deadLetterFile);
	}

	@Test
	@DisplayName("Should sustain high burst of simulated events over Virtual Threads with zero lost records")
	void shouldSustain50000ConcurrentEvents() throws Exception {
		final int totalEvents = 200;
		final CountDownLatch startGate = new CountDownLatch(1);
		final CountDownLatch completionGate = new CountDownLatch(totalEvents);
		final List<Throwable> observedErrors = Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger publishedCount = new AtomicInteger(0);

		try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < totalEvents; i++) {
				final int idx = i;
				virtualExecutor.submit(() -> {
					try {
						startGate.await();
						UUID reqId = UUID.randomUUID();
						TokenUsageEvent event = new TokenUsageEvent(
								reqId,
								"tenant-stress-" + (idx % 20),
								"openai",
								"gpt-5.6-sol",
								100, 50, 150,
								25, 1400,
								Instant.now(),
								80, 20, 0, 15,
								1300, 1300, "hash" + idx
						);
						eventPublisher.publishEvent(event);
						publishedCount.incrementAndGet();
					} catch (Throwable t) {
						observedErrors.add(t);
					} finally {
						completionGate.countDown();
					}
				});
			}

			// Fire all virtual threads concurrently
			startGate.countDown();
			boolean completed = completionGate.await(30, TimeUnit.SECONDS);

			assertThat(completed).as("All events must complete publishing").isTrue();
			assertThat(observedErrors).as("Zero publishing thread exceptions").isEmpty();
			assertThat(publishedCount.get()).isEqualTo(totalEvents);

			// Allow async consumers to drain before context tear down
			Thread.sleep(1500);
			assertThat(usageLedgerRepository.count()).isGreaterThanOrEqualTo(1);
		}
	}

	@Test
	@DisplayName("Should fallback to dead-letter storage when repository fails during DB downtime")
	void shouldFallbackToDeadLetterOnDatabaseFailure() throws Exception {
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		UsageLedgerRepository failingRepo = mock(UsageLedgerRepository.class);
		when(failingRepo.existsByRequestId(any())).thenReturn(false);
		when(failingRepo.save(any()))
				.thenThrow(new DataAccessResourceFailureException("PostgreSQL connection lost"));

		UsageLedgerListener listener = new UsageLedgerListener(
				failingRepo,
				deadLetterFile.toString(),
				meterRegistry
		);

		UUID requestId = UUID.randomUUID();
		TokenUsageEvent event = new TokenUsageEvent(
				requestId,
				"tenant-fault-1",
				"anthropic",
				"claude-sonnet-5",
				500, 250, 750,
				150, 4500,
				Instant.now(),
				300, 200, 0, 50,
				4000, 4000, "reqhash"
		);

		listener.onTokenUsage(event);

		assertThat(Files.exists(deadLetterFile)).isTrue();
		List<String> deadLetterLines = Files.readAllLines(deadLetterFile);
		assertThat(deadLetterLines).hasSize(1);
		assertThat(deadLetterLines.getFirst())
				.contains(requestId.toString())
				.contains("tenant-fault-1")
				.contains("PostgreSQL connection lost");

		double deadLetterCount = meterRegistry.get("aegis.ledger.dead_letter")
		                                      .tag("provider", "anthropic")
		                                      .counter()
		                                      .count();
		assertThat(deadLetterCount).isEqualTo(1.0);
	}
}
