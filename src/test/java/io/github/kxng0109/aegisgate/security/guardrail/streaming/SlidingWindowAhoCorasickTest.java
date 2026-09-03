package io.github.kxng0109.aegisgate.security.guardrail.streaming;

import io.github.kxng0109.aegisgate.security.guardrail.pii.EphemeralPiiVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowAhoCorasick Tests")
class SlidingWindowAhoCorasickTest {

	@Test
	@DisplayName("returns empty or unchanged chunk when chunk is empty or vault is empty")
	void emptyChunkOrVault() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			assertThat(deAnonymizer.processChunk(null)).isEqualTo("");
			assertThat(deAnonymizer.processChunk("")).isEqualTo("");

			// Empty vault passes text straight through
			assertThat(deAnonymizer.processChunk("Hello world")).isEqualTo("Hello world");
			assertThat(deAnonymizer.flush()).isEqualTo("");
		}

		SlidingWindowAhoCorasick nullVaultDeAnonymizer = new SlidingWindowAhoCorasick(null);
		assertThat(nullVaultDeAnonymizer.processChunk("Hello")).isEqualTo("Hello");
		assertThat(nullVaultDeAnonymizer.flush()).isEqualTo("");
	}

	@Test
	@DisplayName("reconstitutes complete surrogate in a single chunk")
	void singleChunkSurrogate() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice Smith");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			String output = deAnonymizer.processChunk("Hello, <PERSON_1>! How are you?");
			assertThat(output).isEqualTo("Hello, Alice Smith! How are you?");
			assertThat(deAnonymizer.flush()).isEqualTo("");
		}
	}

	@Test
	@DisplayName("reconstitutes surrogate split across two consecutive chunks without full buffering")
	void splitAcrossTwoChunks() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice Smith");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			// Chunk 1 has partial surrogate "<PER"
			String out1 = deAnonymizer.processChunk("Hello, <PER");
			assertThat(out1).isEqualTo("Hello, "); // Emitted immediately!

			// Chunk 2 completes the surrogate "SON_1>, welcome!"
			String out2 = deAnonymizer.processChunk("SON_1>, welcome!");
			assertThat(out2).isEqualTo("Alice Smith, welcome!");

			assertThat(deAnonymizer.flush()).isEqualTo("");
		}
	}

	@Test
	@DisplayName("reconstitutes surrogate split across three chunks character-by-character")
	void splitAcrossThreeChunks() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<EMAIL_1>", "support@aegisgate.io");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			String out1 = deAnonymizer.processChunk("Contact <");
			assertThat(out1).isEqualTo("Contact ");

			String out2 = deAnonymizer.processChunk("EMAIL_");
			assertThat(out2).isEqualTo(""); // Held in carry

			String out3 = deAnonymizer.processChunk("1> for help.");
			assertThat(out3).isEqualTo("support@aegisgate.io for help.");

			assertThat(deAnonymizer.flush()).isEqualTo("");
		}
	}

	@Test
	@DisplayName("emits unregistered tags or HTML markup literally")
	void emitsUnregisteredTagsLiterally() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			String out = deAnonymizer.processChunk("<div>Content <PERSON_1> </span>");
			assertThat(out).isEqualTo("<div>Content Alice </span>");
		}
	}

	@Test
	@DisplayName("emits unclosed angle bracket exceeding max lookahead (K=32) literally")
	void exceedsMaxLookahead() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			// Long unclosed text after '<' (> 32 characters)
			String chunk = "<this_is_a_very_long_unclosed_tag_that_exceeds_k";
			String out = deAnonymizer.processChunk(chunk);

			assertThat(out).isNotEmpty();
		}
	}

	@Test
	@DisplayName("emits closed angle bracket tag exceeding max lookahead (K=32) literally")
	void closedTagExceedingMaxLookahead() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			String longTag = "<this_tag_is_way_longer_than_32_characters_long>";
			String out = deAnonymizer.processChunk(longTag);
			assertThat(out).isEqualTo(longTag);
		}
	}

	@Test
	@DisplayName("flush resolves remaining carry buffer if matching surrogate or emits literally")
	void flushResiduals() {
		try (EphemeralPiiVault vault = new EphemeralPiiVault()) {
			vault.store("<PERSON_1>", "Alice Smith");
			SlidingWindowAhoCorasick deAnonymizer = new SlidingWindowAhoCorasick(vault);

			// Feed partial that stops exactly at "<PERSON_1>" without trailing text
			deAnonymizer.processChunk("Hello, <PERSON_1");
			// Flush will check carry and resolve if complete
			String flushed = deAnonymizer.flush();
			assertThat(flushed).isEqualTo("<PERSON_1"); // incomplete surrogate flushed literally
		}
	}
}
