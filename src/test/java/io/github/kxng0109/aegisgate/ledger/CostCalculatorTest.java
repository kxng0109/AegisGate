package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CostCalculator}: exact BigDecimal arithmetic into
 * micro dollars, and the zero cost fallback for unknown models.
 */
@DisplayName("CostCalculator")
class CostCalculatorTest {

	private final ModelPriceCatalog catalog = mock(ModelPriceCatalog.class);
	private final CostCalculator calculator = new CostCalculator(catalog);

	@Test
	@DisplayName("computes input plus output cost in micro dollars")
	void computesCostInMicroDollars() {
		when(catalog.lookup(ProviderType.OPENAI, "gpt-5.6-sol")).thenReturn(
				Optional.of(entry("gpt-5.6-sol", "openai", "0.000004", "0.00002")));

		long micros = calculator.calculate(ProviderType.OPENAI, "gpt-5.6-sol", 1000, 500);

		assertEquals(4 * 1000L + 20 * 500L, micros,
				"1000 input tokens at 4 dollars per million plus 500 output tokens at 20 dollars per million");
	}

	@Test
	@DisplayName("rounds half up to a whole micro dollar")
	void roundsHalfUp() {
		when(catalog.lookup(ProviderType.OPENAI, "m")).thenReturn(
				Optional.of(entry("m", "openai", "0.0000005", "0.0")));

		long micros = calculator.calculate(ProviderType.OPENAI, "m", 1, 0);

		assertEquals(1, micros, "0.5 micro dollars rounds up to 1");
	}

	@Test
	@DisplayName("an unknown model costs zero and never fails")
	void unknownModelCostsZero() {
		when(catalog.lookup(any(), any())).thenReturn(Optional.empty());

		assertEquals(0, calculator.calculate(ProviderType.OPENAI, "brand-new-model", 100, 100));
	}

	private static ModelPricingEntry entry(String modelId, String provider, String input, String output) {
		return new ModelPricingEntry(modelId, provider, "chat",
		                             new BigDecimal(input), new BigDecimal(output));
	}
}