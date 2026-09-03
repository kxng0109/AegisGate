package io.github.kxng0109.aegisgate.security.guardrail.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BytePrefixTrie Tests")
class BytePrefixTrieTest {

	private static final Pattern DUMMY_PATTERN = Pattern.compile(".*");

	@Test
	@DisplayName("null or empty prefix anchors are skipped during construction")
	void nullOrEmptyAnchorsSkipped() {
		List<SecretRule> rules = List.of(
				new SecretRule("rule-null", "desc", null, DUMMY_PATTERN, 3.0, false, false),
				new SecretRule("rule-empty", "desc", "", DUMMY_PATTERN, 3.0, false, false),
				new SecretRule("rule-valid", "desc", "sk-", DUMMY_PATTERN, 3.0, false, false)
		);

		BytePrefixTrie trie = new BytePrefixTrie(rules);
		byte[] data = "test sk-12345".getBytes(StandardCharsets.UTF_8);
		assertThat(trie.containsAnyPrefix(data, 0, data.length)).isTrue();
	}

	@Test
	@DisplayName("ensureCapacity expands tables when inserting many distinct prefixes")
	void ensureCapacityExpansion() {
		List<SecretRule> rules = new ArrayList<>();
		// Generate 300 distinct prefix rules to exceed INITIAL_CAPACITY (256)
		for (int i = 0; i < 300; i++) {
			String anchor = String.format("pref%04d-", i);
			rules.add(new SecretRule("rule-" + i, "desc", anchor, DUMMY_PATTERN, 3.0, false, false));
		}

		BytePrefixTrie trie = new BytePrefixTrie(rules);
		byte[] testData = "content with pref0250-secret payload".getBytes(StandardCharsets.UTF_8);
		assertThat(trie.containsAnyPrefix(testData, 0, testData.length)).isTrue();
	}

	@Test
	@DisplayName("containsAnyPrefix boundary and null checks")
	void containsAnyPrefixBoundaries() {
		List<SecretRule> rules = List.of(
				new SecretRule("sk", "OpenAI", "sk-", DUMMY_PATTERN, 3.0, false, false)
		);
		BytePrefixTrie trie = new BytePrefixTrie(rules);

		byte[] valid = "sk-12345678".getBytes(StandardCharsets.UTF_8);
		assertThat(trie.containsAnyPrefix(null, 0, 10)).isFalse();
		assertThat(trie.containsAnyPrefix(valid, 0, 0)).isFalse();
		assertThat(trie.containsAnyPrefix(valid, 0, -1)).isFalse();
		assertThat(trie.containsAnyPrefix(valid, -1, 5)).isFalse();
		assertThat(trie.containsAnyPrefix(valid, 5, 10)).isFalse(); // overflow
	}

	@Test
	@DisplayName("containsAnyPrefix returns false when buffer contains no matching anchors")
	void containsAnyPrefixNoMatch() {
		List<SecretRule> rules = List.of(
				new SecretRule("sk", "OpenAI", "sk-", DUMMY_PATTERN, 3.0, false, false),
				new SecretRule("ghp", "GitHub", "ghp_", DUMMY_PATTERN, 3.0, false, false)
		);
		BytePrefixTrie trie = new BytePrefixTrie(rules);

		byte[] clean = "This is a completely normal user prompt about java.".getBytes(StandardCharsets.UTF_8);
		assertThat(trie.containsAnyPrefix(clean, 0, clean.length)).isFalse();
	}

	@Test
	@DisplayName("scan collects all candidate matches with accurate start offsets")
	void scanMatchesAndOffsets() {
		SecretRule ruleSk = new SecretRule("sk", "OpenAI Legacy", "sk-", DUMMY_PATTERN, 3.0, false, false);
		SecretRule ruleSkProj = new SecretRule(
				"sk-proj",
				"OpenAI Project",
				"sk-proj-",
				DUMMY_PATTERN,
				4.2,
				true,
				false
		);
		SecretRule ruleGhp = new SecretRule("ghp", "GitHub PAT", "ghp_", DUMMY_PATTERN, 4.0, true, false);

		BytePrefixTrie trie = new BytePrefixTrie(List.of(ruleSk, ruleSkProj, ruleGhp));

		byte[] payload = "keys: ghp_abc and sk-proj-xyz".getBytes(StandardCharsets.UTF_8);
		List<BytePrefixTrie.CandidateMatch> matches = trie.scan(payload, 0, payload.length);

		assertThat(matches).isNotEmpty();
		// Contains ghp_ at index 6
		assertThat(matches).anyMatch(m -> m.rule().id().equals("ghp") && m.startOffset() == 6);
		// Contains sk- and sk-proj- around index 18
		assertThat(matches).anyMatch(m -> m.rule().id().equals("sk-proj") && m.startOffset() == 18);
		assertThat(matches).anyMatch(m -> m.rule().id().equals("sk") && m.startOffset() == 18);
	}

	@Test
	@DisplayName("scan returns empty list on invalid boundary parameters")
	void scanBoundaries() {
		BytePrefixTrie trie = new BytePrefixTrie(List.of());
		byte[] valid = "hello world".getBytes(StandardCharsets.UTF_8);

		assertThat(trie.scan(null, 0, 10)).isEmpty();
		assertThat(trie.scan(valid, 0, 0)).isEmpty();
		assertThat(trie.scan(valid, 0, -5)).isEmpty();
		assertThat(trie.scan(valid, -1, 5)).isEmpty();
		assertThat(trie.scan(valid, 8, 10)).isEmpty(); // overflow
	}

	@Test
	@DisplayName("CandidateMatch record methods coverage")
	void candidateMatchRecordCoverage() {
		SecretRule rule = new SecretRule("id", "desc", "pref", Pattern.compile(".*"), 3.0, false, false);
		BytePrefixTrie.CandidateMatch match1 = new BytePrefixTrie.CandidateMatch(rule, 5);
		BytePrefixTrie.CandidateMatch match2 = new BytePrefixTrie.CandidateMatch(rule, 5);
		BytePrefixTrie.CandidateMatch match3 = new BytePrefixTrie.CandidateMatch(rule, 10);

		assertThat(match1.rule()).isSameAs(rule);
		assertThat(match1.startOffset()).isEqualTo(5);
		assertThat(match1).isEqualTo(match2);
		assertThat(match1).isNotEqualTo(match3);
		assertThat(match1.hashCode()).isEqualTo(match2.hashCode());
		assertThat(match1.toString()).contains("CandidateMatch");
	}
}
