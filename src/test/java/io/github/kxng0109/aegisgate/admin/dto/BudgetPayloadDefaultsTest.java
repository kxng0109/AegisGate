package io.github.kxng0109.aegisgate.admin.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for budget payload null-tolerance: absent caps default to zero (no cap).
 */
@DisplayName("Budget payload defaults")
class BudgetPayloadDefaultsTest {

	@Test
	@DisplayName("create payload defaults null caps to zero")
	void createDefaultsNullCapsToZero() {
		CreateBudgetRequest request = new CreateBudgetRequest("TEAM", "tenant-a", null, null, null);

		assertEquals(0L, request.minuteMicros());
		assertEquals(0L, request.monthMicros());
		assertNull(request.webhookUrl());
	}

	@Test
	@DisplayName("update payload defaults null caps to zero")
	void updateDefaultsNullCapsToZero() {
		UpdateBudgetRequest request = new UpdateBudgetRequest(null, null, null);

		assertEquals(0L, request.minuteMicros());
		assertEquals(0L, request.monthMicros());
		assertNull(request.webhookUrl());
	}
}
