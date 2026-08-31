package io.github.kxng0109.aegisgate.proxy.sse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the periodic SSE flush engine, bound from {@code aegisgate.sse.flush.*}.
 *
 * <p>Values are validated on binding (fail-fast at startup for out-of-range configuration) and can be hot-reloaded at
 * runtime through {@link SseFlushStrategy#updateConfig(SseFlushProperties)}, which the {@link SseFlushConfigReloader}
 * drives by re-binding the same prefix from the {@code Environment}. The defaults implement the adaptive dual-trigger
 * profile: flush when {@code maxLinesPerFlush} lines are buffered or {@code maxIntervalMs} elapsed since the last
 * flush, whichever comes first.</p>
 *
 * @param maxLinesPerFlush             line-count trigger; a flush is issued as soon as this many lines are buffered
 * @param maxIntervalMs                time trigger; a flush is issued when this many milliseconds elapsed since the
 *                                     last flush and at least one line is buffered
 * @param flushBackpressureThresholdMs a flush slower than this counts as downstream backpressure; the stream is aborted
 *                                     so the upstream exchange is cancelled instead of piling memory
 * @param maxBufferBytes               per-connection cap on buffered-but-unflushed bytes; exceeding it aborts the
 *                                     stream, which is the guard against a malicious upstream flooding tiny lines
 * @param maxFlushesPerSecond          per-connection token-bucket cap on flush syscalls
 * @param enabled                      when {@code false} the strategy degrades to the legacy flush-every-line behavior
 */
@ConfigurationProperties("aegisgate.sse.flush")
@Validated
public record SseFlushProperties(
		@Min(1) @Max(10_000) @DefaultValue("16") int maxLinesPerFlush,
		@Min(10) @Max(5_000) @DefaultValue("100") int maxIntervalMs,
		@Min(100) @Max(60_000) @DefaultValue("500") int flushBackpressureThresholdMs,
		@Min(1_024) @Max(1_048_576) @DefaultValue("65536") int maxBufferBytes,
		@Min(100) @Max(10_000) @DefaultValue("1000") int maxFlushesPerSecond,
		@DefaultValue("true") boolean enabled
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 *
	 * <p>The record deliberately has no additional constructors: an extra constructor breaks value-object binding with
	 * the plain {@link org.springframework.boot.context.properties.bind.Binder} used by the hot-reload bridge, so the
	 * fallback defaults live here instead.</p>
	 */
	public static final SseFlushProperties DEFAULTS = new SseFlushProperties(16, 100, 500, 65_536, 1_000, true);
}