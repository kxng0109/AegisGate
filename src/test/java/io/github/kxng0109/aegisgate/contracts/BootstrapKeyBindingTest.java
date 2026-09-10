package io.github.kxng0109.aegisgate.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BootstrapKey indexed binding")
class BootstrapKeyBindingTest {

	@Test
	@DisplayName("indexed properties bind via the canonical constructor despite the compat overload")
	void indexedPropertiesBind() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.bootstrap-keys[0].ownerid", "local")
				                                          .withProperty("gateway.bootstrap-keys[0].name", "local-dev")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[0].plaintextkey",
						                                          "gw-localdevmasterkey0123456789abcde"
				                                          )
				                                          .withProperty("gateway.bootstrap-keys[0].rpmlimit", "120")
				                                          .withProperty("gateway.bootstrap-keys[0].tpmlimit", "500000")
				                                          .withProperty("gateway.bootstrap-keys[0].allowedmodels", "")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[0].allowedproviders",
						                                          ""
				                                          ));

		List<BootstrapKey> keys = Binder.get(environment)
		                                .bind("gateway.bootstrap-keys", Bindable.listOf(BootstrapKey.class))
		                                .orElseThrow(() -> new AssertionError("bootstrap keys did not bind"));

		assertThat(keys).hasSize(1);
		BootstrapKey key = keys.get(0);
		assertThat(key.ownerId()).isEqualTo("local");
		assertThat(key.name()).isEqualTo("local-dev");
		assertThat(key.rpmLimit()).isEqualTo(120);
		assertThat(key.tpmLimit()).isEqualTo(500000);
		assertThat(key.allowedTools()).isEmpty();
		assertThat(key.deniedTools()).isEmpty();
	}

	@Test
	@DisplayName("load-test key binds with the high RPM ceiling")
	void loadTestKeyBinds() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.bootstrap-keys[0].ownerid", "local")
				                                          .withProperty("gateway.bootstrap-keys[0].name", "local-dev")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[0].plaintextkey",
						                                          "gw-localdevmasterkey0123456789abcde"
				                                          )
				                                          .withProperty("gateway.bootstrap-keys[0].rpmlimit", "120")
				                                          .withProperty("gateway.bootstrap-keys[0].tpmlimit", "500000")
				                                          .withProperty("gateway.bootstrap-keys[0].allowedmodels", "")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[0].allowedproviders",
						                                          ""
				                                          )
				                                          .withProperty("gateway.bootstrap-keys[1].ownerid", "load")
				                                          .withProperty("gateway.bootstrap-keys[1].name", "load-test")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[1].plaintextkey",
						                                          "gw-0123456789abcdef0123456789abcdef"
				                                          )
				                                          .withProperty("gateway.bootstrap-keys[1].rpmlimit", "60000")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[1].tpmlimit",
						                                          "10000000"
				                                          )
				                                          .withProperty("gateway.bootstrap-keys[1].allowedmodels", "")
				                                          .withProperty(
						                                          "gateway.bootstrap-keys[1].allowedproviders",
						                                          ""
				                                          ));

		List<BootstrapKey> keys = Binder.get(environment)
		                                .bind("gateway.bootstrap-keys", Bindable.listOf(BootstrapKey.class))
		                                .orElseThrow(() -> new AssertionError("bootstrap keys did not bind"));

		assertThat(keys).hasSize(2);
		BootstrapKey load = keys.get(1);
		assertThat(load.ownerId()).isEqualTo("load");
		assertThat(load.name()).isEqualTo("load-test");
		assertThat(load.plaintextKey()).isEqualTo("gw-0123456789abcdef0123456789abcdef");
		assertThat(load.rpmLimit()).isEqualTo(60000);
		assertThat(load.tpmLimit()).isEqualTo(10000000);
		assertThat(load.allowedTools()).isEmpty();
		assertThat(load.deniedTools()).isEmpty();
	}
}
