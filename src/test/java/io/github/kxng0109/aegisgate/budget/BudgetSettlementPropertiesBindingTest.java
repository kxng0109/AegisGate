package io.github.kxng0109.aegisgate.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding contract for {@link BudgetSettlementProperties}.
 *
 * <p>Locks the {@code gateway.budget.settlement.*} namespace: documented defaults when keys are absent,
 * kebab-case and relaxed underscore binding, startup failure on constraint violations, and — as a
 * regression guard — proof that the legacy mis-nested {@code gateway.notify.settlement.*} keys are
 * ignored instead of bound.
 */
@DisplayName("BudgetSettlementProperties binding contract")
class BudgetSettlementPropertiesBindingTest {

	@EnableConfigurationProperties(BudgetSettlementProperties.class)
	@Configuration(proxyBeanMethods = false)
	static class PropsConfig {
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

	@Test
	@DisplayName("Absent keys bind the documented defaults")
	void absentKeysBindDocumentedDefaults() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(BudgetSettlementProperties.class))
					.as("defaults must mirror the @DefaultValue annotations")
					.isEqualTo(BudgetSettlementProperties.DEFAULTS);
		});
	}

	@Test
	@DisplayName("gateway.budget.settlement keys bind to the record")
	void budgetNamespaceKeysBind() {
		runner.withPropertyValues(
				"gateway.budget.settlement.enabled: false",
				"gateway.budget.settlement.max-tokens-ceiling: 1024",
				"gateway.budget.settlement.hold-ttl-seconds: 120",
				"gateway.budget.settlement.abort-grace-seconds: 10",
				"gateway.budget.settlement.sweeper-batch: 50").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(BudgetSettlementProperties.class))
					.isEqualTo(new BudgetSettlementProperties(false, 1024, 120L, 10L, 50));
		});
	}

	@Test
	@DisplayName("Underscore notation binds via relaxed binding")
	void underscoreNotationBindsViaRelaxedBinding() {
		runner.withPropertyValues(
				"gateway.budget.settlement.max_tokens_ceiling: 2048").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(BudgetSettlementProperties.class).maxTokensCeiling())
					.as("relaxed underscore form must bind like the kebab form")
					.isEqualTo(2048);
		});
	}

	@Test
	@DisplayName("Mis-nested gateway.notify.settlement keys are ignored, never bound")
	void misnestedNotifyNamespaceIsIgnored() {
		runner.withPropertyValues(
				"gateway.notify.settlement.max-tokens-ceiling: 1024").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(BudgetSettlementProperties.class).maxTokensCeiling())
					.as("keys outside the gateway.budget.settlement namespace must not bind")
					.isEqualTo(BudgetSettlementProperties.DEFAULTS.maxTokensCeiling());
		});
	}

	@Test
	@DisplayName("Below-minimum ceiling fails startup via bean validation")
	void belowMinimumCeilingFailsStartup() {
		runner.withPropertyValues(
				"gateway.budget.settlement.max-tokens-ceiling: 0").run(ctx -> {
			assertThat(ctx).hasFailed();
		});
	}
}
