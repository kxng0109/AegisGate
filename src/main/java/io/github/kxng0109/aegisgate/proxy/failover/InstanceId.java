package io.github.kxng0109.aegisgate.proxy.failover;

import java.util.UUID;

/**
 * Stable identity for this gateway instance, used so that exactly one instance owns a HALF_OPEN probe at a time.
 *
 * <p>Prefer {@code spring.application.instance-id} when set; otherwise a random UUID is generated at startup. The value
 * is opaque to Redis and only needs to be unique per live instance.</p>
 */
public record InstanceId(String value) {

	/**
	 * @return a fresh random instance id
	 */
	public static InstanceId generate() {
		return new InstanceId(UUID.randomUUID().toString());
	}
}