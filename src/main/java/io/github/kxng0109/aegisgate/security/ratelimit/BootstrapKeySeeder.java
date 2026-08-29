package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Boot-time seeding of configured bootstrap keys into Redis.
 *
 * <p>Seeding is triggered by {@link ApplicationReadyEvent}  -  i.e. only after the
 * application context (and the embedded web server) is fully ready  -  and retried on a
 * fixed delay by {@link #scheduledReseed()} until it succeeds. The first successful run
 * latches the {@code seeded} flag so later events are no-ops.</p>
 *
 * <p>Failure semantics are deliberately asymmetric:</p>
 * <ul>
 *   <li><b>Fail-open at boot level</b>  -  a Redis outage during startup never fails the
 *       application context. The failure is logged and the scheduled retry keeps
 *       attempting until Redis is reachable.</li>
 *   <li><b>Fail-closed at request level</b>  -  until keys are seeded, authentication
 *       lookups in {@link KeyManagementService} cannot resolve any key, so requests are
 *       rejected; once Redis is reachable, a Redis failure in the request path
 *       propagates and is mapped to HTTP 503 instead of silently treating keys as
 *       absent.</li>
 * </ul>
 *
 * <p>The fixed-delay retry reuses Spring's auto-configured task scheduler (enabled by
 * {@code @EnableScheduling} on {@code AegisGateApplication}); the interval defaults to
 * {@code 30s} and can be tuned via {@code gateway.bootstrap-keys.seed-interval}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapKeySeeder {

	private final KeyManagementService keyManagementService;
	private final GatewayProperties gatewayProperties;

	/**
	 * Latches to {@code true} after the first successful seeding run.
	 */
	private volatile boolean seeded = false;

	/**
	 * Seeds configured bootstrap keys once the application context is fully ready.
	 * Never fails the boot: failures are caught and deferred to the scheduled retry.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void seedOnApplicationReady() {
		seedIfNeeded();
	}

	/**
	 * Retries seeding on a fixed delay until the first successful run. After seeding
	 * has latched, later invocations are cheap no-ops.
	 */
	@Scheduled(fixedDelayString = "${gateway.bootstrap-keys.seed-interval:30s}")
	public void scheduledReseed() {
		seedIfNeeded();
	}

	/**
	 * Runs the seeding operation exactly once: the first invocation whose Redis
	 * interaction succeeds latches {@link #seeded}; any {@link RuntimeException} (for
	 * example, Redis unreachable) is logged and leaves the latch open for the next
	 * attempt. Plaintext keys and digests are never logged.
	 */
	private void seedIfNeeded() {
		if (seeded) {
			return;
		}
		try {
			keyManagementService.seedBootstrapKeys(gatewayProperties);
			seeded = true;
			log.info(
					"Bootstrap keys seeded into Redis ({} key(s))",
			         gatewayProperties.getBootstrapKeys().size()
			);
		} catch (RuntimeException ex) {
			log.warn("Bootstrap key seeding deferred: {}", ex.getMessage());
		}
	}
}