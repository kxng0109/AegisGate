package io.github.kxng0109.aegisgate.proxy;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.config.UpstreamConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyRequestTest {

	private UpstreamConfig upstreamConfig() {
		return new UpstreamConfig(
				"test", "https://api.openai.com/v1",
				new SensitiveString("test-key"), Duration.ofSeconds(5),
				Duration.ofSeconds(5), "/v1/chat/completions"
		);
	}

	@Test
	@DisplayName("constructs with valid arguments")
	void constructsWithValidArguments() {
		ProxyRequest request = new ProxyRequest("{\"model\":\"gpt-4o\"}",
		                                        upstreamConfig(), UUID.randomUUID()
		);

		assertEquals("{\"model\":\"gpt-4o\"}", request.requestBody());
		assertEquals(upstreamConfig().baseUrl(), request.upstreamConfig().baseUrl());
	}

	@Test
	@DisplayName("rejects a null or blank request body")
	void rejectsNullOrBlankBody() {
		assertThrows(IllegalArgumentException.class,
		             () -> new ProxyRequest(null, upstreamConfig(), UUID.randomUUID())
		);
		assertThrows(IllegalArgumentException.class,
		             () -> new ProxyRequest("   ", upstreamConfig(), UUID.randomUUID())
		);
	}

	@Test
	@DisplayName("rejects a null upstream configuration")
	void rejectsNullUpstreamConfig() {
		assertThrows(NullPointerException.class,
		             () -> new ProxyRequest("body", null, UUID.randomUUID())
		);
	}

	@Test
	@DisplayName("rejects a null request id")
	void rejectsNullRequestId() {
		assertThrows(NullPointerException.class,
		             () -> new ProxyRequest("body", upstreamConfig(), null)
		);
	}
}
