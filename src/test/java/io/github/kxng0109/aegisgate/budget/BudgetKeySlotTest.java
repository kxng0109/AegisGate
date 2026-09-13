package io.github.kxng0109.aegisgate.budget;

import java.util.HashSet;
import java.util.Set;

import io.lettuce.core.cluster.SlotHash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves every Redis key the budget gate touches hashes to a single Cluster slot, so the Lua scripts can never
 * fail with CROSSSLOT in a clustered deployment. Testcontainers runs single-node Redis, which does not enforce
 * slot routing — this test closes that parity gap statically using the same slot function Redis uses.
 */
class BudgetKeySlotTest {

	private static final String HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	@DisplayName("all budget keys share one hash slot")
	void allBudgetKeysShareOneSlot() {
		Set<Integer> slots = new HashSet<>();
		slots.add(SlotHash.getSlot(BudgetEnforcer.minuteKey("KEY", HEX, 2_900_000L)));
		slots.add(SlotHash.getSlot(BudgetEnforcer.minuteKey("TEAM", "owner-1", 2_900_000L)));
		slots.add(SlotHash.getSlot(BudgetEnforcer.minuteKey("ORG", "global", 2_900_000L)));
		slots.add(SlotHash.getSlot(BudgetEnforcer.monthKey("KEY", HEX, "2026-09")));
		slots.add(SlotHash.getSlot(BudgetEnforcer.monthKey("TEAM", "owner-1", "2026-09")));
		slots.add(SlotHash.getSlot(BudgetEnforcer.monthKey("ORG", "global", "2026-09")));
		slots.add(SlotHash.getSlot(BudgetEnforcer.cfgKey("KEY", HEX)));
		slots.add(SlotHash.getSlot(BudgetEnforcer.cfgKey("TEAM", "owner-1")));
		slots.add(SlotHash.getSlot(BudgetEnforcer.cfgKey("ORG", "global")));
		slots.add(SlotHash.getSlot("budget:{b:global}:dedupe:some-idempotency-key"));

		assertThat(slots).hasSize(1);
	}

	@Test
	@DisplayName("every budget key carries the global slot tag")
	void everyBudgetKeyCarriesSlotTag() {
		assertThat(BudgetEnforcer.minuteKey("KEY", HEX, 1L)).contains("{b:global}");
		assertThat(BudgetEnforcer.monthKey("KEY", HEX, "2026-09")).contains("{b:global}");
		assertThat(BudgetEnforcer.cfgKey("KEY", HEX)).contains("{b:global}");
	}
}
