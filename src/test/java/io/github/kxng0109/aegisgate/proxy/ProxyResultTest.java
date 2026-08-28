package io.github.kxng0109.aegisgate.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProxyResultTest {

	@Test
	@DisplayName("constructs with every terminal status and exposes its fields")
	void constructsWithEveryStatus() {
		UUID requestId = UUID.randomUUID();
		Duration duration = Duration.ofMillis(42);

		for (ProxyResult.ProxyResultStatus status : ProxyResult.ProxyResultStatus.values()) {
			ProxyResult result = new ProxyResult(
					requestId,
					200,
					1234L,
					duration,
					status,
					"detail"
			);

			assertEquals(requestId, result.requestId());
			assertEquals(200, result.upstreamStatusCode());
			assertEquals(1234L, result.totalBytesProxied());
			assertEquals(duration, result.duration());
			assertEquals(status, result.status());
			assertEquals("detail", result.errorDetail());
		}
	}

	@Test
	@DisplayName("tolerates a null error detail for successful results")
	void toleratesNullErrorDetail() {
		ProxyResult result = new ProxyResult(
				UUID.randomUUID(),
				200,
				0L,
				Duration.ZERO,
				ProxyResult.ProxyResultStatus.SUCCESS,
				null
		);

		assertNotNull(result);
		assertEquals(ProxyResult.ProxyResultStatus.SUCCESS, result.status());
	}
}
