package io.github.kxng0109.aegisgate.mcp.config;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpGatewayProperties HITL secret validation diagnostic")
class McpGatewayPropertiesValidationDiagnosticTest {

	@EnableConfigurationProperties(McpGatewayProperties.class)
	@Configuration(proxyBeanMethods = false)
	static class PropsConfig {
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

	@Test
	@DisplayName("Blank HITL secret fails via cascaded value() constraint")
	void blankSecretFailsWithCustomMessage() {
		runner.withPropertyValues("gateway.mcp.hitl-secret:  ").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure())
					.hasStackTraceContaining("Value required!");
		});
	}

	@Test
	@DisplayName("Short HITL secret fails with the length message")
	void shortSecretFailsWithLengthMessage() {
		runner.withPropertyValues("gateway.mcp.hitl-secret: short").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure())
					.hasStackTraceContaining("must be at least 32 bytes");
		});
	}

	@Test
	@DisplayName("Valid HITL secret starts and masks toString")
	void validSecretStartsAndMasks() {
		runner.withPropertyValues(
				"gateway.mcp.hitl-secret: test-only-hitl-secret-32-bytes-minimum!!").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(McpGatewayProperties.class)).isNotNull();
			assertThat(ctx.getBean(McpGatewayProperties.class).getHitlSecret().toString())
					.isEqualTo("****");
		});
	}

	@Test
	@DisplayName("Shared SensitiveString record must accept short provider keys (no length coupling)")
	void sharedRecordAcceptsShortProviderKeys() {
		Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
		assertThat(validator.validateValue(SensitiveString.class, "value", "sk-test"))
				.as("shared SensitiveString must accept short provider keys")
				.isEmpty();
	}
}
