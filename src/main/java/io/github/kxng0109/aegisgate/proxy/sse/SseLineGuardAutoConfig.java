package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Auto-configuration for the SSE line guard.
 *
 * <p>Registers the line guard factory and the configuration reloader. The
 * factory is always created when the line guard is enabled (the default). When the line guard is disabled, the factory
 * still works but the guard passes every line through without validation, while the body handler still enforces a
 * defensive ceiling against OOM.</p>
 */
@Configuration
@EnableConfigurationProperties(SseLineGuardProperties.class)
public class SseLineGuardAutoConfig {

	@Bean
	SseLineGuardFactory sseLineGuardFactory(
			SseLineGuardProperties properties,
			MeterRegistry registry,
			ObjectMapper objectMapper
	) {
		return new DefaultSseLineGuardFactory(properties, registry, objectMapper);
	}

	@Bean
	SseLineGuardConfigReloader sseLineGuardConfigReloader(
			Environment environment,
			Validator validator,
			SseLineGuardFactory factory
	) {
		return new SseLineGuardConfigReloader(environment, validator, factory);
	}

	/**
	 * Factory for creating per-stream line guards and bounded body handlers.
	 */
	public interface SseLineGuardFactory {
		/**
		 * Creates a new per-stream line guard.
		 *
		 * @param providerType the upstream provider type (the SseLineGuard enum)
		 * @param providerName the provider name for metrics and error events
		 * @param requestId    the request ID for the SSE error event
		 * @return a new line guard instance
		 */
		DefaultSseLineGuard newGuard(
				SseLineGuard.ProviderType providerType,
				String providerName,
				java.util.UUID requestId
		);

		/**
		 * Creates a new body handler for the given provider type.
		 *
		 * @param providerType the upstream provider type
		 * @return a body handler that enforces the configured ceiling
		 */
		BoundedLineBodyHandler bodyHandlerForProvider(
				SseLineGuard.ProviderType providerType
		);

		/**
		 * @return the current properties snapshot
		 */
		SseLineGuardProperties properties();
	}

	/**
	 * Configuration refresher that polls the environment and re-binds properties on a schedule. Follows the same
	 * pattern as {@code SseFlushConfigReloader}.
	 */
	@Validated
	public static class SseLineGuardConfigReloader {

		private static final String PREFIX = "aegisgate.sse.line-guard";
		private static final Logger LOG = LoggerFactory.getLogger(SseLineGuardConfigReloader.class);

		private final Environment environment;
		private final Validator validator;
		private final SseLineGuardFactory factory;
		private SseLineGuardProperties applied;

		SseLineGuardConfigReloader(
				Environment environment,
				Validator validator,
				SseLineGuardFactory factory
		) {
			this.environment = environment;
			this.validator = validator;
			this.factory = factory;
			this.applied = factory.properties();
		}

		@Scheduled(fixedDelayString = "${aegisgate.sse.line-guard.reload-interval:30s}")
		void reload() {
			SseLineGuardProperties rebound;
			try {
				rebound = Binder.get(environment)
				                .bind(PREFIX, SseLineGuardProperties.class)
				                .orElse(SseLineGuardProperties.DEFAULTS);
			} catch (Exception ex) {
				LOG.warn("Ignoring invalid SSE line-guard configuration reload: {}", ex.getMessage());
				return;
			}
			if (rebound.equals(applied)) {
				return;
			}
			Set<ConstraintViolation<SseLineGuardProperties>> violations = validator.validate(rebound);
			if (!violations.isEmpty()) {
				LOG.warn("Ignoring invalid SSE line-guard config: {}", violations);
				return;
			}
			applied = rebound;
			if (factory instanceof DefaultSseLineGuardFactory defaultFactory) {
				defaultFactory.updateProperties(rebound);
			}
			LOG.info("Reloaded SSE line-guard configuration: {}", rebound);
		}
	}
}