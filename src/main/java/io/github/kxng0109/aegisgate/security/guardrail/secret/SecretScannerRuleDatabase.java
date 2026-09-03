package io.github.kxng0109.aegisgate.security.guardrail.secret;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-compiled database of verified cloud, AI, and cryptographic secret signatures.
 */
public final class SecretScannerRuleDatabase {

	private static final List<SecretRule> RULES = List.of(
			new SecretRule(
					"openai-project-key",
					"OpenAI Project-Scoped API Key",
					"sk-proj-",
					Pattern.compile("\\b(sk-proj-[A-Za-z0-9_-]{48,128})\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"openai-admin-key",
					"OpenAI Organization Admin Key",
					"sk-admin-",
					Pattern.compile("\\b(sk-admin-[A-Za-z0-9_-]{48,128})\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"openai-service-account-key",
					"OpenAI Service Account Key",
					"sk-svcacct-",
					Pattern.compile("\\b(sk-svcacct-[A-Za-z0-9_-]{48,128})\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"openai-legacy-key",
					"OpenAI Standard Secret Key",
					"sk-",
					Pattern.compile("\\b(sk-[A-Za-z0-9]{48})\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"anthropic-api-key",
					"Anthropic Standard API Key",
					"sk-ant-api03-",
					Pattern.compile("\\b(sk-ant-api03-[a-zA-Z0-9_\\-]{93}AA)\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"anthropic-admin-key",
					"Anthropic Admin API Key",
					"sk-ant-admin01-",
					Pattern.compile("\\b(sk-ant-admin01-[a-zA-Z0-9_\\-]{93}AA)\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"github-classic-pat",
					"GitHub Classic Personal Access Token",
					"ghp_",
					Pattern.compile("\\b(ghp_[0-9a-zA-Z]{36})\\b"),
					4.0,
					true,
					false
			),
			new SecretRule(
					"github-fine-grained-pat",
					"GitHub Fine-Grained Personal Access Token",
					"github_pat_",
					Pattern.compile("\\b(github_pat_[0-9a-zA-Z_]{82})\\b"),
					4.2,
					true,
					false
			),
			new SecretRule(
					"github-oauth-token",
					"GitHub OAuth Access Token",
					"gho_",
					Pattern.compile("\\b(gho_[0-9a-zA-Z]{36})\\b"),
					4.0,
					true,
					false
			),
			new SecretRule(
					"aws-access-key-id",
					"Amazon Web Services Access Key ID",
					"AKIA",
					Pattern.compile("\\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\\b"),
					2.8,
					false,
					false
			),
			new SecretRule(
					"aws-secret-access-key",
					"Amazon Web Services Secret Access Key",
					"aws",
					Pattern.compile("(?i)aws(.{0,20})?['\"][0-9a-zA-Z/+]{40}['\"]"),
					4.4,
					true,
					false
			),
			new SecretRule(
					"google-api-key",
					"Google Cloud API Key",
					"AIza",
					Pattern.compile("\\b(AIza[0-9A-Za-z-_]{35})\\b"),
					3.8,
					true,
					false
			),
			new SecretRule(
					"slack-bot-token",
					"Slack Bot Token",
					"xoxb-",
					Pattern.compile("\\b(xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9-]*)\\b"),
					3.0,
					true,
					false
			),
			new SecretRule(
					"slack-user-token",
					"Slack User Token",
					"xoxp-",
					Pattern.compile("\\b(xoxp-[0-9]{10,13}-[0-9]{10,13}-[0-9]{10,13}-[a-zA-Z0-9-]{28,34})\\b"),
					3.0,
					true,
					false
			),
			new SecretRule(
					"huggingface-token",
					"HuggingFace User Access Token",
					"hf_",
					Pattern.compile("\\b(hf_[a-zA-Z0-9]{34})\\b"),
					3.8,
					true,
					false
			),
			new SecretRule(
					"stripe-live-key",
					"Stripe Live Secret Key",
					"sk_live_",
					Pattern.compile("\\b((?:sk|rk)_live_[0-9a-zA-Z]{24,99})\\b"),
					3.8,
					true,
					false
			),
			new SecretRule(
					"pki-private-key",
					"PKI Asymmetric Private Key Block",
					"-----BEGIN",
					Pattern.compile("(?i)-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----"),
					0.0,
					false,
					false
			),
			new SecretRule(
					"jwt-signed-token",
					"JSON Web Token (JWT)",
					"ey",
					Pattern.compile("\\b(ey[a-zA-Z0-9_-]{10,}\\.ey[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}={0,2})\\b"),
					3.5,
					true,
					false
			)
	);

	private SecretScannerRuleDatabase() {
	}

	public static List<SecretRule> getRules() {
		return Collections.unmodifiableList(RULES);
	}
}
