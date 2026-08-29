package io.github.kxng0109.aegisgate.ledger;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PricingSyncService}: parsing a sample catalog, filtering to chat oriented entries, upserting,
 * and the non fatal handling of a failed fetch.
 */
@DisplayName("PricingSyncService")
class PricingSyncServiceTest {

	private static final String CATALOG = """
			{
			  "gpt-5.6-sol": {
			    "input_cost_per_token": 4e-06,
			    "output_cost_per_token": 2e-05,
			    "max_input_tokens": 922000,
			    "max_output_tokens": 128000,
			    "litellm_provider": "openai",
			    "mode": "chat"
			  },
			  "claude-sonnet-5": {
			    "input_cost_per_token": 2e-06,
			    "output_cost_per_token": 1e-05,
			    "max_input_tokens": 1000000,
			    "max_output_tokens": 128000,
			    "litellm_provider": "anthropic",
			    "mode": "chat"
			  },
			  "ollama/llama3.2": {
			    "input_cost_per_token": 0.0,
			    "output_cost_per_token": 0.0,
			    "litellm_provider": "ollama",
			    "mode": "completion"
			  },
			  "some-embedding-model": {
			    "input_cost_per_token": 1e-06,
			    "litellm_provider": "openai",
			    "mode": "embedding"
			  },
			  "edge-model": {
			    "input_cost_per_token": 1e-06,
			    "output_cost_per_token": 1e-06,
			    "cache_read_input_token_cost": 2e-08,
			    "cache_creation_input_token_cost": 1e-08,
			    "max_input_tokens": "many",
			    "litellm_provider": "",
			    "mode": "chat"
			  },
			  "mode-less": {
			    "litellm_provider": "ollama",
			    "mode": ""
			  },
			  "odd-model": {
			    "litellm_provider": "",
			    "mode": "",
			    "max_input_tokens": "not-a-number"
			  },
			  "not-an-object": 42
			}
			""";

	private MockWebServer server;
	private ModelPricingRepository repository;
	private ModelPriceCatalog priceCatalog;
	private PricingSyncService service;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		repository = mock(ModelPricingRepository.class);
		priceCatalog = mock(ModelPriceCatalog.class);
		HttpClient httpClient = HttpClient.newBuilder()
		                                  .version(HttpClient.Version.HTTP_2)
		                                  .connectTimeout(Duration.ofSeconds(5))
		                                  .executor(Executors.newVirtualThreadPerTaskExecutor())
		                                  .followRedirects(HttpClient.Redirect.NEVER)
		                                  .build();
		service = new PricingSyncService(
				httpClient, new ObjectMapper(), repository, priceCatalog,
				server.url("/prices.json").toString()
		);
	}

	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}

	@Test
	@DisplayName("parses the catalog and upserts the chat entries")
	void parsesAndUpserts() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody(CATALOG));

		service.refresh();

		verify(repository).upsert(
				eq("gpt-5.6-sol"), eq("openai"), eq("chat"),
				argThat(value -> value.compareTo(new BigDecimal("0.000004")) == 0),
				argThat(value -> value.compareTo(new BigDecimal("0.00002")) == 0),
				eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(922000L), eq(128000L), anyString()
		);
		verify(repository).upsert(
				eq("claude-sonnet-5"), eq("anthropic"), eq("chat"),
				any(), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
				eq(1000000L), eq(128000L), anyString()
		);
		verify(repository).upsert(
				eq("ollama/llama3.2"), eq("ollama"), eq("completion"),
				any(), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
				eq(0L), eq(0L), anyString()
		);
		verify(repository).upsert(
				eq("edge-model"), eq("unknown"), eq("chat"),
				argThat(value -> value.compareTo(new BigDecimal("0.000001")) == 0),
				argThat(value -> value.compareTo(new BigDecimal("0.000001")) == 0),
				argThat(value -> value.compareTo(new BigDecimal("0.00000002")) == 0),
				argThat(value -> value.compareTo(new BigDecimal("0.00000001")) == 0),
				eq(0L), eq(0L), anyString()
		);
		verify(repository).upsert(
				eq("mode-less"), eq("ollama"), eq("chat"),
				any(), any(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
				eq(0L), eq(0L), anyString()
		);
		verify(repository, never()).upsert(
				eq("odd-model"), anyString(), anyString(),
				any(), any(), any(), any(), any(), any(), anyString()
		);
		verify(repository, times(5)).upsert(
				anyString(), anyString(), anyString(),
				any(), any(), any(), any(), any(), any(), anyString()
		);
	}

	@Test
	@DisplayName("a successful sync invalidates the cached catalog snapshot")
	void successfulSyncInvalidatesCatalog() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody(CATALOG));

		service.refresh();

		verify(priceCatalog).invalidate();
	}

	@Test
	@DisplayName("a failed fetch is logged, not thrown")
	void failedFetchIsNonFatal() {
		server.enqueue(new MockResponse().setResponseCode(503).setBody("boom"));

		assertDoesNotThrow(service::refresh);
		verify(repository, never()).upsert(
				anyString(), anyString(), anyString(),
				any(), any(), any(), any(), any(), any(), anyString()
		);
	}

	@Test
	@DisplayName("an unparseable body is logged, not thrown")
	void unparseableBodyIsNonFatal() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("not json"));

		assertDoesNotThrow(service::refresh);
		verify(repository, never()).upsert(
				anyString(), anyString(), anyString(),
				any(), any(), any(), any(), any(), any(), anyString()
		);
	}

	@Test
	@DisplayName("non numeric cost strings are treated as zero")
	void nonNumericCostsAreZero() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("""
				                                                               {"string-cost":{"input_cost_per_token":"0.5","output_cost_per_token":1e-05,
				                                                               "litellm_provider":"openai","mode":"chat"}}"""));

		service.refresh();

		verify(repository).upsert(
				eq("string-cost"), eq("openai"), eq("chat"),
				eq(BigDecimal.ZERO),
				argThat(value -> value.compareTo(new BigDecimal("0.00001")) == 0),
				eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
				eq(0L), eq(0L), anyString()
		);
	}
}