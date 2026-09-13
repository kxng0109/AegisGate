package io.github.kxng0109.aegisgate.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Exact replay for idempotent retries, distinct from the semantic cache (similar-prompt reuse).
 *
 * <p>Two tiers: Redis hot (24h TTL, this instance is evictable — a hot miss re-proxies and the budget dedupe
 * still prevents double-charge) and PostgreSQL durable (forensics and re-warm, never on the latency path).
 * A first request claims the fill lock for the whole upstream flight; concurrent duplicates see
 * {@link InFlight} (HTTP 409); the same key with a different body is {@link FingerprintMismatch} (HTTP 422).
 * Stored payloads above {@link #MAX_BODY_BYTES} are skipped, never truncated.</p>
 */
@Service
public class ReplayService {

	static final String PREFIX = "aegis:replay:";

	static final String FILL_PREFIX = "aegis:replay-fill:";

	static final Duration HOT_TTL = Duration.ofHours(24);

	static final Duration FILL_TTL = Duration.ofMinutes(5);

	static final int MAX_BODY_BYTES = 1_048_576;

	/** Lookup result for one idempotency key + body fingerprint. */
	public sealed interface Lookup permits Hit, Miss, FingerprintMismatch, InFlight {
	}

	/** Stored payload exists and the fingerprint matches: serve it with {@code Idempotent-Replayed}. */
	public record Hit(byte[] body, boolean sseFramed, Instant expiresAt) implements Lookup {
	}

	/** No entry and no in-flight fill: the caller proceeds upstream. */
	public record Miss() implements Lookup {
	}

	/** Same key, different body: the client is reusing a key for another request. */
	public record FingerprintMismatch() implements Lookup {
	}

	/** Another pod/thread is serving the first flight: the caller must not start a second. */
	public record InFlight() implements Lookup {
	}

	private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

	private final StringRedisTemplate cacheTemplate;

	private final ReplayRepository repository;

	private final MeterRegistry meterRegistry;

	public ReplayService(@Qualifier("cacheRedisTemplate") StringRedisTemplate cacheTemplate,
	                     ReplayRepository repository,
	                     @Nullable MeterRegistry meterRegistry) {
		this.cacheTemplate = cacheTemplate;
		this.repository = repository;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Checks the hot tier for a previous completion of this idempotency key.
	 *
	 * @param idempotencyKey validated client key (bounded length/charset by the caller)
	 * @param bodyHashHex    sha256 of the current request body
	 * @return hit, miss, mismatch, or in-flight
	 */
	public Lookup lookup(String idempotencyKey, String bodyHashHex) {
		Map<Object, Object> fields;
		try {
			fields = cacheTemplate.opsForHash().entries(PREFIX + idempotencyKey);
		} catch (RuntimeException ex) {
			// Cache tier down: degrade to re-proxy (budget dedupe still prevents double-charge).
			return new Miss();
		}
		if (fields == null || fields.isEmpty()) {
			Boolean filling = cacheTemplate.hasKey(FILL_PREFIX + idempotencyKey);
			return Boolean.TRUE.equals(filling) ? new InFlight() : new Miss();
		}
		if (!bodyHashHex.equals(stringField(fields, "body_hash"))) {
			count("mismatch");
			return new FingerprintMismatch();
		}
		String payload = stringField(fields, "body");
		if (payload.isEmpty()) {
			return new Miss();
		}
		count("hit");
		return new Hit(payload.getBytes(StandardCharsets.UTF_8),
				"1".equals(stringField(fields, "sse")),
				Instant.now().plus(HOT_TTL));
	}

	/**
	 * Claims the fill for one idempotency key before starting upstream.
	 *
	 * @return {@code true} when this caller owns the flight, {@code false} on a concurrent duplicate
	 */
	public boolean beginFill(String idempotencyKey) {
		try {
			Boolean won = cacheTemplate.opsForValue()
			                           .setIfAbsent(FILL_PREFIX + idempotencyKey, "1", FILL_TTL);
			return Boolean.TRUE.equals(won);
		} catch (RuntimeException ex) {
			// Cache tier down: allow the flight (no replay coordination, still correct).
			return true;
		}
	}

	/**
	 * Releases a fill claim without storing (abort, upstream failure, over-cap payload).
	 */
	public void releaseFill(String idempotencyKey) {
		try {
			cacheTemplate.delete(FILL_PREFIX + idempotencyKey);
		} catch (RuntimeException ex) {
			log.debug("Replay fill release failed for key; TTL bounds the stale claim");
		}
	}

	/**
	 * Stores a completed payload in both tiers and releases the fill claim. First completer wins; oversized
	 * payloads are skipped (never truncated).
	 *
	 * @param idempotencyKey validated client key
	 * @param bodyHashHex    sha256 of the request body (fingerprint for future retries)
	 * @param body           exact bytes to re-deliver
	 * @param sseFramed      whether the payload needs SSE re-framing on serve
	 * @return {@code true} when stored (or cache tier down but PG stored), {@code false} when skipped
	 */
	public boolean store(String idempotencyKey, String bodyHashHex, byte[] body, boolean sseFramed) {
		if (body.length > MAX_BODY_BYTES) {
			count("oversized");
			releaseFill(idempotencyKey);
			return false;
		}
		Instant expiresAt = Instant.now().plus(HOT_TTL).truncatedTo(ChronoUnit.MILLIS);
		try {
			cacheTemplate.opsForHash().putAll(PREFIX + idempotencyKey, Map.of(
					"body", new String(body, StandardCharsets.UTF_8),
					"body_hash", bodyHashHex,
					"sse", sseFramed ? "1" : "0"));
			cacheTemplate.expire(PREFIX + idempotencyKey, HOT_TTL);
		} catch (RuntimeException ex) {
			log.debug("Replay hot-tier store failed; falling back to durable tier only");
		}
		try {
			UUID id = UUID.nameUUIDFromBytes(
					("replay:" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
			repository.save(new ReplayRecord(new ReplayId(id, expiresAt), body, bodyHashHex));
		} catch (RuntimeException ex) {
			log.warn("Replay durable-tier store failed for key");
		} finally {
			releaseFill(idempotencyKey);
		}
		count("stored");
		return true;
	}

	private static String stringField(Map<Object, Object> fields, String name) {
		Object value = fields.get(name);
		return value == null ? "" : value.toString();
	}

	private void count(String outcome) {
		try {
			Counter.builder("aegis.replay.lookups")
			       .tag("outcome", outcome)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}
}
