package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link SseLineGuard}.
 *
 * <p>Enforces per-line byte-length limits, per-stream line-rate and byte-rate limits,
 * and emits SSE error events on violations. The guard is per-stream (one instance per stream) and obtains its
 * configuration from {@link SseLineGuardProperties}.</p>
 *
 * <p>Byte counting uses the UTF-8 byte length of the line, not the Java {@code char} count,
 * so multi-byte characters are charged correctly against the byte rate limit.</p>
 */
public final class DefaultSseLineGuard implements SseLineGuard {

	private final SseLineGuardProperties properties;
	private final MeterRegistry registry;
	private final ObjectMapper objectMapper;
	private final String providerName;
	private final UUID requestId;
	private final long startedNanos;

	private final TokenBucket lineRateLimiter;
	private final TokenBucket byteRateLimiter;

	private final AtomicBoolean rejected = new AtomicBoolean(false);
	private final AtomicReference<String> abortReason = new AtomicReference<>();

	private final Counter lineRejectedTooLong;
	private final Counter lineRejectedLineRate;
	private final Counter lineRejectedByteRate;
	private final Counter upstreamCancelled;
	private final Timer streamDurationOk;
	private final Timer streamDurationAborted;
	private final DistributionSummary lineLengthBytes;

	/**
	 * Creates a new per-stream line guard.
	 *
	 * @param properties   the current configuration
	 * @param registry     the meter registry for metrics (must not be null)
	 * @param objectMapper the object mapper for serializing the SSE error JSON
	 * @param providerType the upstream provider type for limit resolution
	 * @param providerName the provider name for metrics tags
	 * @param requestId    the request ID for the SSE error event
	 */
	DefaultSseLineGuard(
			SseLineGuardProperties properties,
			MeterRegistry registry,
			ObjectMapper objectMapper,
			ProviderType providerType,
			String providerName,
			UUID requestId
	) {
		this.properties = properties;
		this.registry = registry;
		this.objectMapper = objectMapper;
		this.providerName = providerName == null ? "unknown" : providerName;
		this.requestId = requestId == null ? UUID.randomUUID() : requestId;
		this.startedNanos = System.nanoTime();

		SseLineGuard.ProviderType guardType = providerType == null
				? SseLineGuard.ProviderType.UNKNOWN
				: providerType;
		SseLineGuard.ProviderConfig config = properties.perProvider().get(guardType);
		int linesPerSecond;
		int bytesPerSecond;
		if (config != null) {
			linesPerSecond = config.maxLinesPerSecond();
			bytesPerSecond = config.maxBytesPerSecond();
		} else {
			linesPerSecond = 1000;
			bytesPerSecond = 1_048_576;
		}

		this.lineRateLimiter = new TokenBucket(linesPerSecond, linesPerSecond);
		this.byteRateLimiter = new TokenBucket(bytesPerSecond, bytesPerSecond);

		this.lineRejectedTooLong = Counter.builder("sse.line.rejected.count")
		                                  .description(
				                                  "Number of SSE lines rejected because they exceeded the maximum byte length")
		                                  .tag("provider", this.providerName)
		                                  .tag("reason", "LINE_TOO_LONG")
		                                  .tag("action", properties.action().name())
		                                  .register(registry);
		this.lineRejectedLineRate = Counter.builder("sse.line.rejected.count")
		                                   .description(
				                                   "Number of SSE lines rejected because they exceeded the per-line rate limit")
		                                   .tag("provider", this.providerName)
		                                   .tag("reason", "LINE_RATE_LIMIT")
		                                   .tag("action", properties.action().name())
		                                   .register(registry);
		this.lineRejectedByteRate = Counter.builder("sse.line.rejected.count")
		                                   .description(
				                                   "Number of SSE lines rejected because they exceeded the per-byte rate limit")
		                                   .tag("provider", this.providerName)
		                                   .tag("reason", "BYTE_RATE_LIMIT")
		                                   .tag("action", properties.action().name())
		                                   .register(registry);
		this.upstreamCancelled = Counter.builder("sse.upstream.cancelled.count")
		                                .description("Number of upstream streams cancelled by the line guard")
		                                .tag("provider", this.providerName)
		                                .register(registry);
		this.streamDurationOk = Timer.builder("sse.stream.duration.seconds")
		                             .description("Total SSE relay stream lifetime for streams that completed normally")
		                             .publishPercentileHistogram()
		                             .tag("provider", this.providerName)
		                             .tag("status", "ok")
		                             .register(registry);
		this.streamDurationAborted = Timer.builder("sse.stream.duration.seconds")
		                                  .description("Total SSE relay stream lifetime for streams that were aborted")
		                                  .publishPercentileHistogram()
		                                  .tag("provider", this.providerName)
		                                  .tag("status", "aborted")
		                                  .register(registry);
		this.lineLengthBytes = DistributionSummary.builder("sse.line.length.bytes")
		                                          .description("Distribution of accepted SSE line byte lengths")
		                                          .baseUnit("bytes")
		                                          .tag("provider", this.providerName)
		                                          .register(registry);
	}

	@Override
	public List<String> checkLine(String line, ProviderType provider) {
		if (!properties.enabled()) {
			return List.of(line);
		}

		int maxLineBytes = effectiveMaxLineBytes(provider);
		int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;

		if (lineBytes > maxLineBytes) {
			return rejectTooLong(maxLineBytes, lineBytes);
		}

		if (!lineRateLimiter.tryAcquire(1)) {
			return rejectLineRate(maxLineBytes, lineBytes);
		}

		if (!byteRateLimiter.tryAcquire(lineBytes)) {
			return rejectByteRate(maxLineBytes, lineBytes);
		}

		lineLengthBytes.record(lineBytes);
		return List.of(line);
	}

	private int effectiveMaxLineBytes(ProviderType provider) {
		SseLineGuard.ProviderType guardType = provider == null
				? SseLineGuard.ProviderType.UNKNOWN
				: provider;
		SseLineGuard.ProviderConfig config = properties.perProvider().get(guardType);
		if (config != null) {
			return config.maxLineBytes();
		}
		return properties.globalDefaultBytes();
	}

	private List<String> reject(String code, Counter counter, int limitBytes, int actualBytes) {
		if (properties.action() == Action.REJECT_LINE_AND_CLOSE) {
			rejected.set(true);
		}
		counter.increment();
		if (properties.action() == Action.REJECT_LINE_CONTINUE) {
			return List.of();
		}
		return buildErrorEvent(code, limitBytes, actualBytes);
	}

	private List<String> rejectTooLong(int limitBytes, int actualBytes) {
		return reject("LINE_TOO_LONG", lineRejectedTooLong, limitBytes, actualBytes);
	}

	private List<String> rejectLineRate(int limitBytes, int actualBytes) {
		return reject("LINE_RATE_LIMIT", lineRejectedLineRate, limitBytes, actualBytes);
	}

	private List<String> rejectByteRate(int limitBytes, int actualBytes) {
		return reject("BYTE_RATE_LIMIT", lineRejectedByteRate, limitBytes, actualBytes);
	}

	private List<String> buildErrorEvent(String code, int limitBytes, int actualBytes) {
		String json = buildErrorJson(code, limitBytes, actualBytes);
		return List.of("event: error", "data: " + json, "");
	}

	private String buildErrorJson(String code, int limitBytes, int actualBytes) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("code", code);
		payload.put(
				"message",
				"SSE line exceeds configured maximum of " + limitBytes + " bytes (actual: " + actualBytes + ")"
		);
		payload.put("limit", limitBytes);
		payload.put("actual", actualBytes);
		payload.put("provider", providerName);
		payload.put("requestId", requestId.toString());
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException ex) {
			return "{\"code\":\"" + code + "\",\"message\":\"SSE line limit exceeded\""
					+ ",\"limit\":" + limitBytes + ",\"actual\":" + actualBytes
					+ ",\"provider\":\"" + providerName + "\"}";
		}
	}

	@Override
	public boolean isRejected() {
		return rejected.get();
	}

	@Override
	public void onStreamComplete() {
		streamDurationOk.record(Duration.ofNanos(System.nanoTime() - startedNanos));
	}

	@Override
	public void onStreamAbort(String reason) {
		upstreamCancelled.increment();
		streamDurationAborted.record(Duration.ofNanos(System.nanoTime() - startedNanos));
		abortReason.set(reason);
	}

	@Override
	public ConfigSnapshot config() {
		return new ConfigSnapshot(
				properties.globalDefaultBytes(),
				Map.copyOf(properties.perProvider()),
				properties.safetyMarginPercent(),
				properties.action()
		);
	}

	/**
	 * @return the abort reason if the stream was aborted, null otherwise
	 */
	@Nullable
	String abortReason() {
		return abortReason.get();
	}
}