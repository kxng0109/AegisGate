package io.github.kxng0109.aegisgate.security.ratelimit;

import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class BootstrapKeySeederTest {

	private final KeyManagementService keyManagementService = mock(KeyManagementService.class);
	private final GatewayProperties gatewayProperties = mock(GatewayProperties.class);

	private BootstrapKeySeeder seeder() {
		when(gatewayProperties.getBootstrapKeys()).thenReturn(List.of());
		return new BootstrapKeySeeder(keyManagementService, gatewayProperties);
	}

	@Test
	void seedOnApplicationReadySeedsOnceAndLatches() {
		BootstrapKeySeeder seeder = seeder();

		seeder.seedOnApplicationReady();
		seeder.seedOnApplicationReady();

		verify(keyManagementService, times(1)).seedBootstrapKeys(gatewayProperties);
	}

	@Test
	void scheduledReseedSeedsOnceAndLatches() {
		BootstrapKeySeeder seeder = seeder();

		seeder.scheduledReseed();
		seeder.scheduledReseed();

		verify(keyManagementService, times(1)).seedBootstrapKeys(gatewayProperties);
	}

	@Test
	void failedSeedingIsCaughtAndRetriedByScheduledReseed() {
		BootstrapKeySeeder seeder = seeder();
		doThrow(new RuntimeException("redis down"))
				.doNothing()
				.when(keyManagementService).seedBootstrapKeys(gatewayProperties);

		// First attempt: Redis down. Must not propagate and must not latch.
		assertDoesNotThrow(seeder::seedOnApplicationReady);
		verify(keyManagementService, times(1)).seedBootstrapKeys(gatewayProperties);

		// Retry: succeeds and latches.
		seeder.scheduledReseed();
		verify(keyManagementService, times(2)).seedBootstrapKeys(gatewayProperties);

		// Latched: further events are no-ops.
		seeder.seedOnApplicationReady();
		verify(keyManagementService, times(2)).seedBootstrapKeys(gatewayProperties);
	}

	@Test
	void emptyBootstrapKeyListSeedsTriviallyAndLatches() {
		BootstrapKeySeeder seeder = seeder();

		seeder.scheduledReseed();
		seeder.seedOnApplicationReady();

		verify(keyManagementService, times(1)).seedBootstrapKeys(gatewayProperties);
	}
}