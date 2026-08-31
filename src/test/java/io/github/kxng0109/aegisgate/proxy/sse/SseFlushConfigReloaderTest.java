package io.github.kxng0109.aegisgate.proxy.sse;

import io.github.kxng0109.aegisgate.proxy.sse.TestServletOutputStreams.RecordingServletOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SseFlushConfigReloader}: a changed prefix is re-bound, validated, and pushed into the strategy;
 * an invalid reload is rejected; an unchanged reload is skipped.
 */
@DisplayName("SseFlushConfigReloader")
class SseFlushConfigReloaderTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("a changed, valid configuration is applied to the strategy")
	void appliesChangedConfiguration() throws Exception {
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						SseFlushProperties.DEFAULTS, new SimpleMeterRegistry(), new MutableNanoSource(), 0, 30_000, 100)
		) {
			StandardEnvironment environment = environmentWith("aegisgate.sse.flush.max-lines-per-flush", "32");
			SseFlushConfigReloader reloader = new SseFlushConfigReloader(environment, strategy, VALIDATOR);

			reloader.reload();

			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);
			for (int i = 0; i < 31; i++) {
				strategy.onWrite(out, 10);
			}
			assertEquals(0, out.flushCount(), "31 lines must stay under the reloaded 32-line threshold");
			strategy.onWrite(out, 10);
			assertEquals(1, out.flushCount());
		}
	}

	@Test
	@DisplayName("an invalid reload keeps the previous configuration")
	void invalidReloadIsRejected() throws Exception {
		try (
				AdaptiveSseFlushStrategy strategy = new AdaptiveSseFlushStrategy(
						SseFlushProperties.DEFAULTS, new SimpleMeterRegistry(), new MutableNanoSource(), 0, 30_000, 100)
		) {
			StandardEnvironment environment = environmentWith("aegisgate.sse.flush.max-lines-per-flush", "0");
			SseFlushConfigReloader reloader = new SseFlushConfigReloader(environment, strategy, VALIDATOR);

			reloader.reload();

			RecordingServletOutputStream out = new RecordingServletOutputStream();
			strategy.register(out);
			for (int i = 0; i < 16; i++) {
				strategy.onWrite(out, 10);
			}
			assertEquals(1, out.flushCount(), "the original 16-line threshold must keep serving");
		}
	}

	@Test
	@DisplayName("an unchanged reload is skipped after the first application")
	void unchangedReloadIsSkipped() {
		SseFlushStrategy strategy = mock(SseFlushStrategy.class);
		StandardEnvironment environment = environmentWith("aegisgate.sse.flush.max-lines-per-flush", "32");
		SseFlushConfigReloader reloader = new SseFlushConfigReloader(environment, strategy, VALIDATOR);

		reloader.reload();
		reloader.reload();

		verify(strategy, times(1)).updateConfig(new SseFlushProperties(32, 100, 500, 65_536, 1_000, true));
	}

	@Test
	@DisplayName("a missing prefix falls back to the defaults and triggers no update")
	void missingPrefixFallsBackToDefaults() {
		SseFlushStrategy strategy = mock(SseFlushStrategy.class);
		SseFlushConfigReloader reloader = new SseFlushConfigReloader(new StandardEnvironment(), strategy, VALIDATOR);

		reloader.reload();

		verify(strategy, never()).updateConfig(any());
	}

	private static StandardEnvironment environmentWith(String key, String value) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test").withProperty(key, value));
		return environment;
	}
}