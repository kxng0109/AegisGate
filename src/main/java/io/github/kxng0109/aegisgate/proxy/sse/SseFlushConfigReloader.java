package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Hot-reload bridge for {@link SseFlushProperties}.
 *
 * <p>Spring Cloud's {@code @RefreshScope} / {@code EnvironmentChangeEvent} machinery is not on the classpath, so this
 * component re-binds the {@code aegisgate.sse.flush} prefix from the {@link Environment} on a schedule (default 30s,
 * overridable with {@code aegisgate.sse.flush.reload-interval}) and pushes changed, valid snapshots into the strategy
 * through {@link SseFlushStrategy#updateConfig(SseFlushProperties)}, which swaps the configuration atomically. An
 * invalid reload (for example a value outside the {@code @Min}/{@code @Max} bounds) is rejected with a warning and the
 * previous configuration keeps serving.</p>
 */
@Component
public class SseFlushConfigReloader {

	private static final Logger log = LoggerFactory.getLogger(SseFlushConfigReloader.class);

	private static final String PREFIX = "aegisgate.sse.flush";

	private final Environment environment;

	private final SseFlushStrategy strategy;

	private final Validator validator;

	private volatile SseFlushProperties applied;

	/**
	 * Creates the reloader.
	 *
	 * @param environment source the prefix is re-bound from
	 * @param strategy    target of the reload
	 * @param validator   validates the re-bound snapshot before it is applied
	 */
	public SseFlushConfigReloader(Environment environment, SseFlushStrategy strategy, Validator validator) {
		this.environment = environment;
		this.strategy = strategy;
		this.validator = validator;
		this.applied = SseFlushProperties.DEFAULTS;
	}

	/**
	 * Re-binds the flush configuration and applies it when it changed and validates.
	 */
	@Scheduled(fixedDelayString = "${aegisgate.sse.flush.reload-interval:30s}")
	void reload() {
		SseFlushProperties rebound = Binder.get(environment)
		                                   .bind(PREFIX, SseFlushProperties.class)
		                                   .orElse(SseFlushProperties.DEFAULTS);
		if (rebound.equals(applied)) {
			return;
		}
		Set<ConstraintViolation<SseFlushProperties>> violations = validator.validate(rebound);
		if (!violations.isEmpty()) {
			log.warn("Ignoring invalid SSE flush configuration reload: {}", violations);
			return;
		}
		applied = rebound;
		strategy.updateConfig(rebound);
		log.info("Reloaded SSE flush configuration: {}", rebound);
	}
}