package io.github.kxng0109.aegisgate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig
@EnableConfigurationProperties(UpstreamConfig.class)
@ContextConfiguration(initializers = ConfigDataApplicationContextInitializer.class)
class UpstreamConfigTest {

	@Autowired
	UpstreamConfig upstreamConfig;

	@Test
	@DisplayName("binds all required fields from application.properties")
	void bindsRequiredFields() {
		assertAll(
				() -> assertEquals("lmstudio", upstreamConfig.name()),
				() -> assertEquals("https://api.openai.com/v1", upstreamConfig.baseUrl()),
				() -> assertEquals("****", upstreamConfig.apiKey().toString())
		);
	}

	@Test
	@DisplayName("binds connectTimeout with default 60s when not specified")
	void bindsConnectTimeoutDefault() {
		assertEquals(Duration.ofSeconds(60), upstreamConfig.connectTimeout());
	}

	@Test
	@DisplayName("binds requestTimeout with default 60s when not specified")
	void bindsRequestTimeoutDefault() {
		assertEquals(Duration.ofSeconds(60), upstreamConfig.requestTimeout());
	}

	@Test
	@DisplayName("binds chatCompletionsPath with default /v1/chat/completions when not specified")
	void bindsChatCompletionsPathDefault() {
		assertEquals("/v1/chat/completions", upstreamConfig.chatCompletionsPath());
	}

	@Test
	@DisplayName("rejects blank name")
	void rejectsBlankName() {
		assertThrows(IllegalStateException.class, () -> {
			             new UpstreamConfig("", "https://api.example.com", new SensitiveString("key"), Duration.ofSeconds(10),
			                                Duration.ofSeconds(10), "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("rejects blank baseUrl")
	void rejectsBlankBaseUrl() {
		assertThrows(IllegalStateException.class, () -> {
			             new UpstreamConfig("test", "", new SensitiveString("key"), Duration.ofSeconds(10), Duration.ofSeconds(10),
			                                "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("rejects invalid baseUrl scheme")
	void rejectsInvalidBaseUrlScheme() {
		assertThrows(IllegalStateException.class, () -> {
			             new UpstreamConfig("test", "ftp://api.example.com", new SensitiveString("key"), Duration.ofSeconds(10),
			                                Duration.ofSeconds(10), "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("rejects blank apiKey")
	void rejectsBlankApiKey() {
		assertThrows(IllegalStateException.class, () -> {
			             new UpstreamConfig("test", "https://api.example.com", new SensitiveString(""), Duration.ofSeconds(10),
			                                Duration.ofSeconds(10), "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("rejects negative connectTimeout")
	void rejectsNegativeConnectTimeout() {
		assertThrows(IllegalArgumentException.class, () -> {
			             new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"), Duration.ofSeconds(-1),
			                                Duration.ofSeconds(10), "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("rejects negative requestTimeout")
	void rejectsNegativeRequestTimeout() {
		assertThrows(IllegalArgumentException.class, () -> {
			             new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"), Duration.ofSeconds(10),
			                                Duration.ofSeconds(-1), "/path"
			             );
		             }
		);
	}

	@Test
	@DisplayName("record contract: equality and hashCode work correctly")
	void recordContractHolds() {
		UpstreamConfig config1 = new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"),
		                                            Duration.ofSeconds(10), Duration.ofSeconds(10), "/path"
		);
		UpstreamConfig config2 = new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"),
		                                            Duration.ofSeconds(10), Duration.ofSeconds(10), "/path"
		);
		UpstreamConfig config3 = new UpstreamConfig("test2", "https://api.example.com", new SensitiveString("key"),
		                                            Duration.ofSeconds(10), Duration.ofSeconds(10), "/path"
		);

		assertEquals(config1, config2);
		assertEquals(config1.hashCode(), config2.hashCode());
		assertNotEquals(config1, config3);
	}

	@Test
	@DisplayName("rejects a chatCompletionsPath that does not start with '/'")
	void rejectsPathWithoutLeadingSlash() {
		assertThrows(IllegalStateException.class, () -> {
			new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"),
					Duration.ofSeconds(10), Duration.ofSeconds(10), "path"
			);
		});
	}

	@Test
	@DisplayName("rejects a blank chatCompletionsPath")
	void rejectsBlankPath() {
		assertThrows(IllegalStateException.class, () -> {
			new UpstreamConfig("test", "https://api.example.com", new SensitiveString("key"),
					Duration.ofSeconds(10), Duration.ofSeconds(10), "   "
			);
		});
	}

	@Test
	@DisplayName("defaults chatCompletionsPath when null")
	void defaultsPathWhenNull() {
		UpstreamConfig config = new UpstreamConfig("test", "https://api.example.com",
				new SensitiveString("key"), Duration.ofSeconds(10), Duration.ofSeconds(10), null);
		assertEquals("/v1/chat/completions/", config.chatCompletionsPath());
	}

	@Test
	@DisplayName("defaults connectTimeout when null")
	void defaultsConnectTimeoutWhenNull() {
		UpstreamConfig config = new UpstreamConfig("test", "https://api.example.com",
				new SensitiveString("key"), null, Duration.ofSeconds(10), "/path");
		assertEquals(Duration.ofSeconds(60), config.connectTimeout());
	}

	@Test
	@DisplayName("defaults requestTimeout when null")
	void defaultsRequestTimeoutWhenNull() {
		UpstreamConfig config = new UpstreamConfig("test", "https://api.example.com",
				new SensitiveString("key"), Duration.ofSeconds(10), null, "/path");
		assertEquals(Duration.ofSeconds(60), config.requestTimeout());
	}

	@Test
	@DisplayName("accepts an http:// (non-TLS) baseUrl")
	void acceptsHttpBaseUrl() {
		UpstreamConfig config = new UpstreamConfig("test", "http://api.example.com",
				new SensitiveString("key"), Duration.ofSeconds(10), Duration.ofSeconds(10), "/path");
		assertEquals("http://api.example.com", config.baseUrl());
	}
}