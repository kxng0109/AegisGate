package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FinOpsPromptCacheCalculator Granular Telemetry Test Suite")
class FinOpsPromptCacheCalculatorTest {

	private ModelPriceCatalog catalog;
	private FinOpsPromptCacheCalculator calculator;

	@BeforeEach
	void setUp() {
		catalog = mock(ModelPriceCatalog.class);
		calculator = new FinOpsPromptCacheCalculator(catalog);
	}

	@Test
	@DisplayName("Anthropic caching applies 1.25x write surcharge and 0.10x read discount")
	void shouldCalculateAnthropicPromptCachingAccurately() {
		// Model: input = $0.000002 / token, output = $0.000010 / token
		// Base: $2.00 / 1M in, $10.00 / 1M out
		// Write: $2.50 / 1M ($0.0000025)
		// Read: $0.20 / 1M ($0.0000002)
		ModelPricingEntry entry = new ModelPricingEntry(
				"claude-sonnet-5",
				"anthropic",
				"chat",
				new BigDecimal("0.000002"),
				new BigDecimal("0.000010")
		);
		when(catalog.lookup(ProviderType.ANTHROPIC, "claude-sonnet-5")).thenReturn(Optional.of(entry));

		// 1000 prompt tokens total: 200 uncached ($0.0004), 300 write ($0.00075), 500 read ($0.0001) -> In = $0.00125
		// 500 completion tokens: 500 * $0.000010 = $0.005 -> Total = $0.00625 = 6250 micros
		// List cost: 1000 * $0.000002 + 500 * $0.000010 = $0.002 + $0.005 = $0.007 = 7000 micros
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown breakdown = calculator.calculateBreakdown(
				ProviderType.ANTHROPIC,
				"claude-sonnet-5",
				1000, 500,
				200, 500, 300
		);

		assertThat(breakdown.listCostMicros()).isEqualTo(7000L);
		assertThat(breakdown.billedCostMicros()).isEqualTo(6250L);
		assertThat(breakdown.cacheSavingsMicros()).isEqualTo(750L);
	}

	@Test
	@DisplayName("DeepSeek caching applies 0.00x write surcharge and 0.10x read discount")
	void shouldCalculateDeepSeekPromptCachingAccurately() {
		// Model: input = $0.0000002 / token, output = $0.0000012 / token
		ModelPricingEntry entry = new ModelPricingEntry(
				"deepseek-r1",
				"deepseek",
				"chat",
				new BigDecimal("0.0000002"),
				new BigDecimal("0.0000012")
		);
		when(catalog.lookup(ProviderType.DEEPSEEK, "deepseek-r1")).thenReturn(Optional.of(entry));

		// 10,000 prompt tokens: 2,000 uncached ($0.0004), 8,000 read ($0.00016) -> In = $0.00056
		// 1,000 completion tokens: 1,000 * $0.0000012 = $0.0012 -> Total = $0.00176 = 1760 micros
		// List cost: 10,000 * 0.0000002 + 1000 * 0.0000012 = 0.002 + 0.0012 = 0.0032 = 3200 micros
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown breakdown = calculator.calculateBreakdown(
				ProviderType.DEEPSEEK,
				"deepseek-r1",
				10_000, 1_000,
				2_000, 8_000, 0
		);

		assertThat(breakdown.listCostMicros()).isEqualTo(3200L);
		assertThat(breakdown.billedCostMicros()).isEqualTo(1760L);
		assertThat(breakdown.cacheSavingsMicros()).isEqualTo(1440L);
	}

	@Test
	@DisplayName("Returns zero breakdown when model is not found in catalog")
	void shouldReturnZeroOnUnknownModel() {
		when(catalog.lookup(ProviderType.OPENAI, "unknown-model")).thenReturn(Optional.empty());

		FinOpsPromptCacheCalculator.FinOpsCostBreakdown breakdown = calculator.calculateBreakdown(
				ProviderType.OPENAI,
				"unknown-model",
				100, 50, 100, 0, 0
		);

		assertThat(breakdown).isEqualTo(FinOpsPromptCacheCalculator.FinOpsCostBreakdown.ZERO);
	}

	@Test
	@DisplayName("Handles explicit pricing entry rates, OpenAI read rate, and generic provider fallback")
	void shouldHandleExplicitRatesAndProviderFallbacks() {
		// Explicit rates
		ModelPricingEntry explicitEntry = new ModelPricingEntry(
				"explicit-model", "custom", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000008"),
				new BigDecimal("0.0000005"), new BigDecimal("0.000003")
		);
		when(catalog.lookup(ProviderType.OPENAI, "explicit-model")).thenReturn(Optional.of(explicitEntry));

		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b1 = calculator.calculateBreakdown(
				ProviderType.OPENAI, "explicit-model",
				1000, 500, 200, 500, 300
		);
		assertThat(b1.billedCostMicros()).isEqualTo(5550L);

		// OpenAI 50% read discount fallback
		ModelPricingEntry openAiEntry = new ModelPricingEntry(
				"gpt-4o", "openai", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000010")
		);
		when(catalog.lookup(ProviderType.OPENAI, "gpt-4o")).thenReturn(Optional.of(openAiEntry));

		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b2 = calculator.calculateBreakdown(
				ProviderType.OPENAI, "gpt-4o",
				1000, 500, 500, 500, 0
		);
		// uncached: 500 * 2 = 1000, read: 500 * 1 = 500, out: 500 * 10 = 5000 -> 6500 micros
		assertThat(b2.billedCostMicros()).isEqualTo(6500L);

		// Generic Ollama fallback (no write surcharge, no read discount)
		ModelPricingEntry ollamaEntry = new ModelPricingEntry(
				"llama3.2", "ollama", "chat",
				new BigDecimal("0.000001"), new BigDecimal("0.000002")
		);
		when(catalog.lookup(ProviderType.OLLAMA, "llama3.2")).thenReturn(Optional.of(ollamaEntry));

		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b3 = calculator.calculateBreakdown(
				ProviderType.OLLAMA, "llama3.2",
				100, 50, 50, 50, 0
		);
		assertThat(b3.billedCostMicros()).isGreaterThan(0L);
	}
}
