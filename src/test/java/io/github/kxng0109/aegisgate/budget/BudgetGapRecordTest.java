package io.github.kxng0109.aegisgate.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the gap-record entity: field mapping and JPA-spec constructors.
 */
@DisplayName("BudgetGapRecord")
class BudgetGapRecordTest {

	@Test
	@DisplayName("constructor maps every audited field")
	void constructorMapsFields() {
		BudgetGapRecord record = new BudgetGapRecord(
				"hold-1", "KEY", "hex", 10_000L, 4_000L, "2026-09", "2026-09", "EXPIRED");

		assertThat(record.getId()).isNotNull();
		assertThat(record.getHoldId()).isEqualTo("hold-1");
		assertThat(record.getLevel()).isEqualTo("KEY");
		assertThat(record.getSubjectId()).isEqualTo("hex");
		assertThat(record.getHeldMicros()).isEqualTo(10_000L);
		assertThat(record.getSettledMicros()).isEqualTo(4_000L);
		assertThat(record.getOrigMonth()).isEqualTo("2026-09");
		assertThat(record.getSettleMonth()).isEqualTo("2026-09");
		assertThat(record.getReason()).isEqualTo("EXPIRED");
		assertThat(record.getCreatedAt()).isNotNull();
	}
}
