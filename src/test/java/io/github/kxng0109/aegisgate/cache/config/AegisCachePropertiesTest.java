package io.github.kxng0109.aegisgate.cache.config;

import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AegisCacheProperties")
class AegisCachePropertiesTest {

	@Test
	@DisplayName("getters and setters configure all properties correctly")
	void gettersAndSetters() {
		AegisCacheProperties props = new AegisCacheProperties();

		props.setEnabled(false);
		assertThat(props.isEnabled()).isFalse();

		props.setDefaultScope(CacheScope.USER);
		assertThat(props.getDefaultScope()).isEqualTo(CacheScope.USER);

		props.setTtl(Duration.ofHours(12));
		assertThat(props.getTtl()).isEqualTo(Duration.ofHours(12));

		AegisCacheProperties.ExactCacheProperties exact = new AegisCacheProperties.ExactCacheProperties();
		exact.setL0InMemorySize(1000);
		exact.setL0InMemoryTtl(Duration.ofSeconds(30));
		exact.setL1RedisEnabled(false);
		props.setExact(exact);

		assertThat(props.getExact().getL0InMemorySize()).isEqualTo(1000);
		assertThat(props.getExact().getL0InMemoryTtl()).isEqualTo(Duration.ofSeconds(30));
		assertThat(props.getExact().isL1RedisEnabled()).isFalse();

		AegisCacheProperties.SemanticCacheProperties semantic = new AegisCacheProperties.SemanticCacheProperties();
		semantic.setEnabled(false);
		semantic.setEmbeddingModel("bge-small");
		semantic.setSimilarityThreshold(0.85);
		semantic.setMaxTurnCountback(2);
		semantic.setPolarityGuardEnabled(false);
		semantic.setEntityGuardEnabled(false);
		semantic.setTemperatureFloor(0.2);
		props.setSemantic(semantic);

		assertThat(props.getSemantic().isEnabled()).isFalse();
		assertThat(props.getSemantic().getEmbeddingModel()).isEqualTo("bge-small");
		assertThat(props.getSemantic().getSimilarityThreshold()).isEqualTo(0.85);
		assertThat(props.getSemantic().getMaxTurnCountback()).isEqualTo(2);
		assertThat(props.getSemantic().isPolarityGuardEnabled()).isFalse();
		assertThat(props.getSemantic().isEntityGuardEnabled()).isFalse();
		assertThat(props.getSemantic().getTemperatureFloor()).isEqualTo(0.2);
	}
}
