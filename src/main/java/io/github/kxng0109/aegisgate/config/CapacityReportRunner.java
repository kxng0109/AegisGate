package io.github.kxng0109.aegisgate.config;

import io.github.kxng0109.aegisgate.ledger.LedgerExecutorProperties;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingProperties;
import io.github.kxng0109.aegisgate.proxy.sse.SseCapacityProperties;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs the effective capacity ceilings once at startup so operators can see exactly what the active profile configured
 * — a ceiling you cannot see is a ceiling you will misconfigure.
 *
 * <p>Runs late in startup and never fails boot: reporting must not break serving.</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class CapacityReportRunner implements ApplicationRunner {

	private final SseCapacityProperties sseCapacity;
	private final LedgerExecutorProperties ledgerExecutor;
	private final RateLimitProperties rateLimit;
	private final EmbeddingProperties embeddings;

	@Override
	public void run(ApplicationArguments args) {
		log.info("AegisGate effective capacity ceilings: {}", formatReport());
	}

	/**
	 * Formats the one-line capacity report.
	 *
	 * @return human-readable effective ceilings
	 */
	String formatReport() {
		return "sse[max-connections=%d, tick-ms=%d, watchdog-ms=%d] ".formatted(
				sseCapacity.maxConnections(), sseCapacity.tickPeriodMs(), sseCapacity.watchdogTimeoutMs())
				+ "ledger[executor=%d/%d/q%d/await%ds] ".formatted(
				ledgerExecutor.corePoolSize(), ledgerExecutor.maxPoolSize(),
				ledgerExecutor.queueCapacity(), ledgerExecutor.awaitTerminationSeconds()
		)
				+ "ratelimit[window-ms=%d, tokens=%d..%d, key-cache=%d/%ds] ".formatted(
				rateLimit.windowMillis(), rateLimit.minEstimatedTokens(), rateLimit.maxEstimatedTokens(),
				rateLimit.keyCacheMaximumSize(), rateLimit.keyCacheTtlSeconds()
		)
				+ "embeddings[max-batch=%d, fan-out=%d]".formatted(
				embeddings.maxBatchItems(), embeddings.maxConcurrentSubRequests());
	}
}
