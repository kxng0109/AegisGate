package io.github.kxng0109.aegisgate.ledger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Asynchronous support for the ledger.
 *
 * <p>Virtual threads are enabled globally, so Spring Boot's default async
 * executor would be an unbounded virtual thread executor. That is the wrong tool for database writes, which should be
 * rate limited and bounded: a dedicated pool with a fixed number of threads and a bounded queue keeps a burst of
 * traffic from piling unbounded work onto the ledger. When the queue fills, the
 * {@link ThreadPoolExecutor.CallerRunsPolicy} runs the task on the publishing thread rather than dropping the usage
 * record, which preserves billing data at the cost of a small amount of publishing time.</p>
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(LedgerExecutorProperties.class)
public class LedgerConfig {

	/**
	 * Creates the dedicated ledger executor bean.
	 *
	 * @param properties bounded pool ceilings
	 * @return the executor used by {@code @Async("ledgerExecutor")}
	 */
	@Bean("ledgerExecutor")
	public ThreadPoolTaskExecutor ledgerExecutor(LedgerExecutorProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ledger-");
		executor.setCorePoolSize(properties.corePoolSize());
		executor.setMaxPoolSize(Math.max(properties.corePoolSize(), properties.maxPoolSize()));
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(properties.awaitTerminationSeconds());
		return executor;
	}
}