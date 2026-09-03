package io.github.kxng0109.aegisgate.security.guardrail.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IngressSecretScanner Tests")
class IngressSecretScannerTest {

	private final IngressSecretScanner scanner = new IngressSecretScanner();

	@Test
	@DisplayName("clean scan result on null or empty body")
	void cleanOnNullOrEmptyBody() {
		assertThat(scanner.scan(null, null).detected()).isFalse();
		assertThat(scanner.scan(new byte[0], "").detected()).isFalse();
	}

	@Test
	@DisplayName("clean scan result on prompt without any anchor prefixes (Stage 1 fast path)")
	void cleanOnNoPrefixPrompt() {
		String cleanPrompt = "{\"messages\":[{\"role\":\"user\",\"content\":\"Explain the difference between synchronous and asynchronous programming.\"}]}";
		byte[] bytes = cleanPrompt.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, cleanPrompt);
		assertThat(result.detected()).isFalse();
	}

	@Test
	@DisplayName("scans successfully with null textPayload triggering lazy UTF-8 decoding")
	void lazyDecodingWithNullTextPayload() {
		String prompt = "{\"prompt\": \"Here is my key sk-proj-bC7xY8zL2pQ9wE3rT6yU8iO5aS7dF9gH2jK4lZ6xX8cV0bN1mQ3wE5rT7yU9iO1\"}";
		byte[] bytes = prompt.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, null);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("openai-project-key");
	}

	@Test
	@DisplayName("low entropy false-positive is rejected even when matching prefix and regex")
	void lowEntropyTokenRejected() {
		// Repeats 'a' causing entropy to be ~1.2, far below 4.2 threshold
		String dummyKey = "sk-proj-" + "a".repeat(60);
		String payload = "{\"key\": \"" + dummyKey + "\"}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isFalse();
	}

	@Test
	@DisplayName("detects OpenAI Project Key and computes mask, fingerprint, and jsonPath")
	void detectsOpenAiProjectKey() {
		String validKey = "sk-proj-aB9zY1kL0pQ8wE2rT5yU7iO4aS6dF8gH1jK3lZ5xX7cV9bN0mQ2wE4rT6yU8iO0";
		String payload = "{\"credentials\": {\"apiKey\": \"" + validKey + "\"}}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("openai-project-key");
		assertThat(result.description()).contains("OpenAI Project");
		assertThat(result.maskedToken()).startsWith("sk-proj-").endsWith("*");
		assertThat(result.tokenFingerprint()).hasSize(64); // SHA-256 hex
		assertThat(result.jsonPath()).isEqualTo("/apiKey");
	}

	@Test
	@DisplayName("detects Anthropic API Key")
	void detectsAnthropicKey() {
		// Exactly 93 characters between sk-ant-api03- and AA: 63 chars + 30 chars = 93 chars
		String antKey = "sk-ant-api03-aB9zY1kL0pQ8wE2rT5yU7iO4aS6dF8gH1jK3lZ5xX7cV9bN0mQ2wE4rT6yU8iO01a2b3c4d5e6f7g8h9i0j1k2l3m4n5xAA";
		String payload = "my key is " + antKey;
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("anthropic-api-key");
		assertThat(result.jsonPath()).isEqualTo("/"); // Root path when no enclosing JSON key
	}

	@Test
	@DisplayName("detects GitHub Classic Personal Access Token")
	void detectsGitHubToken() {
		// Exactly 36 characters after ghp_: 10 digits + 26 alphabet characters = 36 chars
		String ghp = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";
		String payload = "{\"github_token\": \"" + ghp + "\"}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("github-classic-pat");
		assertThat(result.jsonPath()).isEqualTo("/github_token");
	}

	@Test
	@DisplayName("detects AWS Access Key ID")
	void detectsAwsAccessKeyId() {
		String awsKey = "AKIAIOSFODNN7EXAMPLE";
		String payload = "{\"aws_id\": \"" + awsKey + "\"}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("aws-access-key-id");
	}

	@Test
	@DisplayName("detects Google Cloud API Key")
	void detectsGoogleApiKey() {
		String googleKey = "AIzaSyD-aB9zY1kL0pQ8wE2rT5yU7iO4aS6dF8g";
		String payload = "{\"gcp\": \"" + googleKey + "\"}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("google-api-key");
	}

	@Test
	@DisplayName("detects PKI Private Key block")
	void detectsPkiPrivateKey() {
		String pki = "-----BEGIN RSA PRIVATE KEY-----";
		String payload = "{\"cert\": \"" + pki + "\"}";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("pki-private-key");
	}

	@Test
	@DisplayName("custom rule with Luhn verification accepts valid card and rejects invalid")
	void customRuleWithLuhn() {
		SecretRule cardRule = new SecretRule(
				"card-rule",
				"Test Card",
				"4532",
				Pattern.compile("\\b(4532\\d{12})\\b"),
				0.0,
				false,
				true // checkLuhn = true
		);
		IngressSecretScanner customScanner = new IngressSecretScanner(List.of(cardRule));

		// Valid card: 4532015112830366
		byte[] validBytes = "card: 4532015112830366".getBytes(StandardCharsets.UTF_8);
		SecretScanResult validResult = customScanner.scan(validBytes, "card: 4532015112830366");
		assertThat(validResult.detected()).isTrue();
		assertThat(validResult.ruleId()).isEqualTo("card-rule");

		// Invalid card: 4532015112830367
		byte[] invalidBytes = "card: 4532015112830367".getBytes(StandardCharsets.UTF_8);
		SecretScanResult invalidResult = customScanner.scan(invalidBytes, "card: 4532015112830367");
		assertThat(invalidResult.detected()).isFalse();
	}

	@Test
	@DisplayName("masks short tokens (<= 8 chars) entirely with asterisks")
	void masksShortTokensEntirely() {
		SecretRule shortRule = new SecretRule(
				"short-rule",
				"Short Token",
				"sh-",
				Pattern.compile("\\b(sh-[0-9]{4})\\b"),
				0.0,
				false,
				false
		);
		IngressSecretScanner customScanner = new IngressSecretScanner(List.of(shortRule));

		byte[] bytes = "token sh-1234".getBytes(StandardCharsets.UTF_8);
		SecretScanResult result = customScanner.scan(bytes, "token sh-1234");
		assertThat(result.detected()).isTrue();
		assertThat(result.maskedToken()).isEqualTo("*******"); // 7 chars, all '*'
	}

	@Test
	@DisplayName("scans rule with null prefix anchor and non-participating capture group")
	void ruleWithNullPrefixAndNonParticipatingGroup() {
		// Pattern with group 1 that doesn't participate in the match
		SecretRule rule = new SecretRule(
				"null-prefix-rule",
				"Rule Without Anchor",
				null,
				Pattern.compile("(unused_group)?\\b(token-[0-9]{4})\\b"),
				0.0,
				false,
				false
		);
		// Pre-populate trie with an active anchor to pass Stage 1
		SecretRule dummyAnchor = new SecretRule(
				"anchor",
				"desc",
				"trig-",
				Pattern.compile("trig-[0-9]"),
				0.0,
				false,
				false
		);
		IngressSecretScanner customScanner = new IngressSecretScanner(List.of(dummyAnchor, rule));

		String payload = "trig-1 and unquoted\": token-9999";
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = customScanner.scan(bytes, payload);
		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isIn("anchor", "null-prefix-rule");
	}
}
