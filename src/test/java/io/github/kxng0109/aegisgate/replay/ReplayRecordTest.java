package io.github.kxng0109.aegisgate.replay;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the replay record entity: field mapping and JPA-spec constructors.
 */
@DisplayName("ReplayRecord")
class ReplayRecordTest {

	@Test
	@DisplayName("constructor maps every stored field")
	void constructorMapsFields() {
		ReplayRecord record = new ReplayRecord(
				new ReplayId(UUID.randomUUID(), Instant.now()),
				new byte[]{1, 2}, "abc123");

		assertThat(record.getId()).isNotNull();
		assertThat(record.getBody()).isEqualTo(new byte[]{1, 2});
		assertThat(record.getBodyHash()).isEqualTo("abc123");
		assertThat(record.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("no-argument constructor exists for JPA")
	void noArgumentConstructorExists() {
		assertThat(new ReplayRecord().getId()).isNull();
	}
}
