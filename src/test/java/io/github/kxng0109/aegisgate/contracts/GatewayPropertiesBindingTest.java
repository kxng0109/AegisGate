package io.github.kxng0109.aegisgate.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the multi provider configuration in application.yml binds correctly, in particular the model aliases
 * whose names contain dots. Dotted YAML keys are quoted so they bind as literal map keys rather than nested property
 * paths.
 */
@SpringBootTest
@DisplayName("GatewayProperties binding")
class GatewayPropertiesBindingTest {

	@Autowired
	private GatewayProperties gatewayProperties;

	@Test
	@DisplayName("providers bind from application.yml")
	void providersBind() {
		assertNotNull(gatewayProperties.getProviders());
		assertTrue(
				gatewayProperties.getProviders().containsKey("openai"),
				"the openai provider must be configured"
		);
		assertTrue(
				gatewayProperties.getProviders().containsKey("ollama"),
				"the ollama provider must be configured"
		);
	}

	@Test
	@DisplayName("aliases bind as literal map keys")
	void dottedAliasesBind() {
		assertNotNull(gatewayProperties.getAliases());
		assertTrue(
				gatewayProperties.getAliases().containsKey("gpt-56-luna"),
				"the alias gpt-56-luna must bind as a single key"
		);
		assertTrue(
				gatewayProperties.getAliases().containsKey("gpt-56-terra"),
				"the alias gpt-56-terra must bind as a single key"
		);
		assertTrue(
				gatewayProperties.getAliases().containsKey("gpt-56-sol"),
				"the alias gpt-56-sol must bind as a single key"
		);
	}

	@Test
	@DisplayName("alias chains and strategies bind correctly")
	void aliasDetailsBind() {
		ModelAlias fast = gatewayProperties.getAliases().get("fast");
		assertNotNull(fast);
		assertEquals(3, fast.chain().size(), "the fast alias must chain three providers");
		assertEquals(FailoverStrategy.SEQUENTIAL, fast.strategy());
	}
}