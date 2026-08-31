package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Default factory for creating per-stream line guards and bounded body handlers.
 *
 * <p>The {@code properties} field is {@code volatile} so the hot-reload mechanism
 * in {@code SseLineGuardAutoConfig.SseLineGuardConfigReloader} can update it without locking; new guards created after
 * a reload pick up the updated configuration, while existing guards continue to use the snapshot they were constructed
 * with.</p>
 */
public final class DefaultSseLineGuardFactory implements SseLineGuardAutoConfig.SseLineGuardFactory {

	private volatile SseLineGuardProperties properties;
	private final MeterRegistry registry;
	private final ObjectMapper objectMapper;

	public DefaultSseLineGuardFactory(
			SseLineGuardProperties properties,
			MeterRegistry registry,
			ObjectMapper objectMapper
	) {
		this.properties = properties;
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	@Override
	public DefaultSseLineGuard newGuard(
			SseLineGuard.ProviderType providerType,
			String providerName,
			UUID requestId
	) {
		return new DefaultSseLineGuard(
				properties,
				registry,
				objectMapper,
				providerType,
				providerName,
				requestId == null ? UUID.randomUUID() : requestId
		);
	}

	@Override
	public BoundedLineBodyHandler bodyHandlerForProvider(
			SseLineGuard.ProviderType providerType
	) {
		int ceilingBytes = resolveCeilingBytes(providerType);
		return new BoundedLineBodyHandler(ceilingBytes, StandardCharsets.UTF_8);
	}

	@Override
	public SseLineGuardProperties properties() {
		return properties;
	}

	/**
	 * Replaces the active configuration. Called by {@code SseLineGuardConfigReloader} after a successful hot-reload.
	 */
	void updateProperties(SseLineGuardProperties newProps) {
		this.properties = newProps;
	}

	/**
	 * Resolves the body handler's ceiling bytes for a provider type.
	 *
	 * <p>The body handler enforces a slightly higher byte limit than the
	 * line guard (which enforces the exact configured limit) so that the guard's precise error reporting fires first;
	 * the body handler is the defence-in-depth backstop that prevents the OOM itself.</p>
	 */
	private int resolveCeilingBytes(SseLineGuard.ProviderType type) {
		SseLineGuard.ProviderConfig config = properties.perProvider().get(type);
		int limit = (config != null) ? config.maxLineBytes() : properties.globalDefaultBytes();
		long ceiling = (long) limit + ((long) limit * properties.safetyMarginPercent() / 100L);
		return (int) Math.min(1_048_576L, ceiling);
	}
}