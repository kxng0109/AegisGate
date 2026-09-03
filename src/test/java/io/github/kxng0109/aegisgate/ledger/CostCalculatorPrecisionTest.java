package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CostCalculator Micro-Dollar Precision & Rounding Test Suite")
class CostCalculatorPrecisionTest {

	private ModelPriceCatalog priceCatalog;
	private CostCalculator costCalculator;

	@BeforeEach
	void setUp() {
		priceCatalog = mock(ModelPriceCatalog.class);
		costCalculator = new CostCalculator(priceCatalog);
	}

	@ParameterizedTest(name = "[{index}] prompt={0}, completion={1}, inputCostPerToken={2}, outputCostPerToken={3} -> expectedMicros={4}")
	@CsvSource({
			"1000, 500, 0.000002, 0.000008, 6000",
			"1, 0, 0.00000015, 0.00000060, 0",
			"3, 0, 0.00000015, 0.00000060, 0",
			"4, 0, 0.00000015, 0.00000060, 1",
			"1, 0, 0.00000050, 0.00000100, 1",
			"0, 0, 0.000002, 0.000008, 0",
			"1000000, 0, 0.000015, 0.000060, 15000000"
	})
	@DisplayName("CostCalculator computes micro-dollars without IEEE-754 precision drift")
	void shouldCalculateMicroDollarsAccurately(
			long promptTokens,
			long completionTokens,
			String inputCostPerToken,
			String outputCostPerToken,
			long expectedCostUsdMicros
	) {
		String model = "pricing-test-model";
		ModelPricingEntry pricingEntry = new ModelPricingEntry(
				ProviderType.OPENAI.name().toLowerCase(),
				"openai",
				"chat",
				new BigDecimal(inputCostPerToken),
				new BigDecimal(outputCostPerToken)
		);
		when(priceCatalog.lookup(ProviderType.OPENAI, model)).thenReturn(Optional.of(pricingEntry));

		long calculatedMicros = costCalculator.calculate(ProviderType.OPENAI, model, promptTokens, completionTokens);

		assertThat(calculatedMicros)
				.as("Micro-dollar calculation must match expected half-up rounded value")
				.isEqualTo(expectedCostUsdMicros);
	}

	@org.junit.jupiter.api.Test
	@DisplayName("CostCalculator computes 7-argument prompt caching across providers and explicit rates")
	void shouldCalculatePromptCachingBranches() {
		// 1. Explicit rates in pricing entry
		ModelPricingEntry explicitEntry = new ModelPricingEntry(
				"explicit-model", "custom", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000008"),
				new BigDecimal("0.0000005"), new BigDecimal("0.000003")
		);
		when(priceCatalog.lookup(ProviderType.OPENAI, "explicit-model")).thenReturn(Optional.of(explicitEntry));

		long explicitCost = costCalculator.calculate(
				ProviderType.OPENAI, "explicit-model",
				1000, 500, 200, 500, 300
		);
		// uncached: 200 * 2 = 400
		// write: 300 * 3 = 900
		// read: 500 * 0.5 = 250
		// out: 500 * 8 = 4000 -> Total = 5550 micros
		assertThat(explicitCost).isEqualTo(5550L);

		// 2. Anthropic fallback multipliers
		ModelPricingEntry anthropicEntry = new ModelPricingEntry(
				"claude-test", "anthropic", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000010")
		);
		when(priceCatalog.lookup(ProviderType.ANTHROPIC, "claude-test")).thenReturn(Optional.of(anthropicEntry));
		long anthropicCost = costCalculator.calculate(
				ProviderType.ANTHROPIC, "claude-test",
				1000, 500, 200, 500, 300
		);
		assertThat(anthropicCost).isEqualTo(6250L);

		// 3. DeepSeek fallback multipliers
		ModelPricingEntry deepseekEntry = new ModelPricingEntry(
				"deepseek-test", "deepseek", "chat",
				new BigDecimal("0.000002"), new BigDecimal("0.000010")
		);
		when(priceCatalog.lookup(ProviderType.DEEPSEEK, "deepseek-test")).thenReturn(Optional.of(deepseekEntry));
		long deepseekCost = costCalculator.calculate(
				ProviderType.DEEPSEEK, "deepseek-test",
				1000, 500, 200, 800, 0
		);
		// uncached: 200 * 2 = 400, read: 800 * 0.2 = 160, out: 500 * 10 = 5000 -> 5560 micros
		assertThat(deepseekCost).isEqualTo(5560L);

		// 4. Default provider (e.g. Ollama)
		ModelPricingEntry ollamaEntry = new ModelPricingEntry(
				"ollama-test", "ollama", "chat",
				new BigDecimal("0.000001"), new BigDecimal("0.000002")
		);
		when(priceCatalog.lookup(ProviderType.OLLAMA, "ollama-test")).thenReturn(Optional.of(ollamaEntry));
		long ollamaCost = costCalculator.calculate(
				ProviderType.OLLAMA, "ollama-test",
				100, 50, 50, 50, 0
		);
		assertThat(ollamaCost).isGreaterThan(0L);

		// 5. Unknown model returns 0
		when(priceCatalog.lookup(ProviderType.OPENAI, "missing")).thenReturn(Optional.empty());
		assertThat(costCalculator.calculate(ProviderType.OPENAI, "missing", 100, 50, 50, 50, 0)).isEqualTo(0L);
	}
}
