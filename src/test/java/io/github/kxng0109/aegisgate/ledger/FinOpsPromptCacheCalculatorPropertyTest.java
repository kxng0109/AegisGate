package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FinOpsPromptCacheCalculator signed-savings and FOCUS 1.4 invariants")
class FinOpsPromptCacheCalculatorPropertyTest {

	private static final BigDecimal IN = new BigDecimal("0.000002");
	private static final BigDecimal OUT = new BigDecimal("0.000010");

	private ModelPriceCatalog catalog;
	private FinOpsPromptCacheCalculator calculator;

	@BeforeEach
	void setUp() {
		catalog = mock(ModelPriceCatalog.class);
		calculator = new FinOpsPromptCacheCalculator(catalog);
		when(catalog.lookup(ProviderType.ANTHROPIC, "claude-sonnet-5"))
				.thenReturn(Optional.of(new ModelPricingEntry(
						"claude-sonnet-5", "anthropic", "chat", IN, OUT)));
	}

	private FinOpsPromptCacheCalculator.FinOpsCostBreakdown calc(
			long total, long completion, long uncached, long read, long write) {
		return calculator.calculateBreakdown(
				ProviderType.ANTHROPIC, "claude-sonnet-5", total, completion, uncached, read, write);
	}

	@Test
	@DisplayName("cold-cache write-only yields negative savings (list 2000, billed 2500, savings -500)")
	void coldCacheWriteOnlyIsNegative() {
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(1000, 0, 0, 0, 1000);
		assertThat(b.listCostMicros()).isEqualTo(2000L);
		assertThat(b.billedCostMicros()).isEqualTo(2500L);
		assertThat(b.effectiveCostMicros()).isEqualTo(2500L);
		assertThat(b.cacheSavingsMicros()).isEqualTo(-500L);
		assertThat(b.cacheSavingsMicros()).isNegative();
	}

	@Test
	@DisplayName("read-heavy yields positive savings (1000 reads: list 2000, billed 200, savings +1800)")
	void readHeavyIsPositive() {
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(1000, 0, 0, 1000, 0);
		assertThat(b.listCostMicros()).isEqualTo(2000L);
		assertThat(b.billedCostMicros()).isEqualTo(200L);
		assertThat(b.cacheSavingsMicros()).isEqualTo(1800L);
	}

	@Test
	@DisplayName("zero tokens yields all zero (no division, no rounding artifact)")
	void zeroTokensIsZero() {
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(0, 0, 0, 0, 0);
		assertThat(b.listCostMicros()).isZero();
		assertThat(b.billedCostMicros()).isZero();
		assertThat(b.effectiveCostMicros()).isZero();
		assertThat(b.cacheSavingsMicros()).isZero();
	}

	@Test
	@DisplayName("write surcharge exactly offset by reads yields zero")
	void exactOffsetIsZero() {
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(23, 0, 0, 5, 18);
		assertThat(b.listCostMicros()).isEqualTo(b.billedCostMicros());
		assertThat(b.cacheSavingsMicros()).isZero();
	}

	@ParameterizedTest(name = "invariant tot={0} unc={2} read={3} write={4}")
	@CsvSource({
			"1000, 500, 200, 500, 300",
			"1000, 0, 0, 0, 1000",
			"1000, 0, 0, 1000, 0",
			"23, 0, 0, 5, 18",
			"0, 0, 0, 0, 0",
			"1000000, 1000000, 500000, 300000, 200000"
	})
	@DisplayName("effective equals billed for every usage charge with no covering charges")
	void effectiveEqualsBilledInvariant(
			long total, long completion, long uncached, long read, long write) {
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(total, completion, uncached, read, write);
		assertThat(b.effectiveCostMicros()).isEqualTo(b.billedCostMicros());
		assertThat(b.cacheSavingsMicros()).isEqualTo(b.listCostMicros() - b.billedCostMicros());
	}

	@Test
	@DisplayName("micro-dollar rounding is HALF_UP at the 0.5-micro boundary")
	void roundingIsHalfUp() {
		ModelPriceCatalog catalog2 = mock(ModelPriceCatalog.class);
		when(catalog2.lookup(ProviderType.ANTHROPIC, "tiny"))
				.thenReturn(Optional.of(new ModelPricingEntry(
						"tiny", "anthropic", "chat", new BigDecimal("0.0000005"), BigDecimal.ZERO)));
		FinOpsPromptCacheCalculator calc2 = new FinOpsPromptCacheCalculator(catalog2);
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown half =
				calc2.calculateBreakdown(ProviderType.ANTHROPIC, "tiny", 1, 0, 1, 0, 0);
		assertThat(half.listCostMicros()).isEqualTo(1L);

		when(catalog2.lookup(ProviderType.ANTHROPIC, "tiny"))
				.thenReturn(Optional.of(new ModelPricingEntry(
						"tiny", "anthropic", "chat", new BigDecimal("0.00000049"), BigDecimal.ZERO)));
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown down =
				calc2.calculateBreakdown(ProviderType.ANTHROPIC, "tiny", 1, 0, 1, 0, 0);
		assertThat(down.listCostMicros()).isZero();

		assertThat(BigDecimal.valueOf(15, 7)).isEqualByComparingTo(new BigDecimal("0.0000015"));
		BigDecimal micros = new BigDecimal("0.0000015")
				.multiply(FinOpsPromptCacheCalculator.MICRO_DOLLARS_PER_DOLLAR);
		assertThat(micros.setScale(0, RoundingMode.HALF_UP).longValue()).isEqualTo(2L);
		assertThat(FinOpsPromptCacheCalculator.MICRO_DOLLARS_PER_DOLLAR)
				.isEqualByComparingTo(new BigDecimal("1000000"));
	}

	@Test
	@DisplayName("FOCUS 1.4 record shape maps to List, Effective, and Billed Cost columns")
	void focusFieldOrderAndNaming() {
		String[] names = Arrays.stream(
				                       FinOpsPromptCacheCalculator.FinOpsCostBreakdown.class.getRecordComponents())
		                       .map(RecordComponent::getName)
		                       .toArray(String[]::new);
		assertThat(names).containsExactly(
				"listCostMicros", "effectiveCostMicros", "billedCostMicros", "cacheSavingsMicros");
		FinOpsPromptCacheCalculator.FinOpsCostBreakdown b = calc(1000, 500, 200, 500, 300);
		assertThat(b.listCostMicros()).isEqualTo(7000L);
		assertThat(b.billedCostMicros()).isEqualTo(6250L);
		assertThat(b.effectiveCostMicros()).isEqualTo(b.billedCostMicros());
		assertThat(b.cacheSavingsMicros()).isEqualTo(b.listCostMicros() - b.billedCostMicros());
		assertThat(
				Arrays.stream(FinOpsPromptCacheCalculator.FinOpsCostBreakdown.class.getRecordComponents())
				      .map(component -> component.getType().getSimpleName())
				      .collect(Collectors.toSet()))
				.containsExactly("long");
	}
}
