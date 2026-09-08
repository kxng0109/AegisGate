package io.github.kxng0109.aegisgate.config;

import io.github.kxng0109.aegisgate.ledger.LedgerExecutorProperties;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingProperties;
import io.github.kxng0109.aegisgate.proxy.sse.SseCapacityProperties;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("CapacityReportRunner")
class CapacityReportRunnerTest {

	@Test
	@DisplayName("report renders default ceilings")
	void rendersDefaults() {
		CapacityReportRunner runner = new CapacityReportRunner(
				SseCapacityProperties.DEFAULTS,
				LedgerExecutorProperties.DEFAULTS,
				RateLimitProperties.DEFAULTS,
				EmbeddingProperties.DEFAULTS
		);

		String report = runner.formatReport();

		assertThat(report).contains("max-connections=10000");
		assertThat(report).contains("executor=2/4/q1000/await10s");
		assertThat(report).contains("window-ms=60000");
		assertThat(report).contains("max-batch=2048");
	}

	@Test
	@DisplayName("report renders overridden ceilings")
	void rendersOverrides() {
		CapacityReportRunner runner = new CapacityReportRunner(
				new SseCapacityProperties(60_000, 5, 15_000L),
				new LedgerExecutorProperties(8, 16, 10_000, 30),
				new RateLimitProperties(30_000L, 1, 500_000, 100_000, 60),
				new EmbeddingProperties(512, 8)
		);

		String report = runner.formatReport();

		assertThat(report).contains("max-connections=60000");
		assertThat(report).contains("executor=8/16/q10000/await30s");
		assertThat(report).contains("window-ms=30000");
		assertThat(report).contains("max-batch=512");
	}

	@Test
	@DisplayName("run completes without failing boot")
	void runCompletes() throws Exception {
		CapacityReportRunner runner = new CapacityReportRunner(
				SseCapacityProperties.DEFAULTS,
				LedgerExecutorProperties.DEFAULTS,
				RateLimitProperties.DEFAULTS,
				EmbeddingProperties.DEFAULTS
		);

		runner.run(mock(ApplicationArguments.class));
	}
}
