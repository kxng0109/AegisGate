package io.github.kxng0109.aegisgate.mcp.security;

import io.github.kxng0109.aegisgate.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.aegisgate.security.guardrail.secret.SecretScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Ingress parameter scanner and egress output sanitizer for Model Context Protocol tool executions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpGuardrailScanner {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern INDIRECT_INJECTION_MARKERS = Pattern.compile(
			"(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|disregard\\s+(?:all\\s+)?prior\\s+prompts|<\\|im_start\\|>|\\[SYSTEM\\]:)"
	);

	private final IngressSecretScanner secretScanner;
	private final ObjectMapper objectMapper;

	/**
	 * Scans tool argument payload for leaked credentials, API keys, and high-entropy secrets.
	 *
	 * @param arguments tool arguments JSON node
	 * @return scan result indicating whether a secret was detected
	 */
	public SecretScanResult scanArguments(@Nullable JsonNode arguments) {
		if (arguments == null || arguments.isNull() || arguments.isEmpty()) {
			return SecretScanResult.clean();
		}
		try {
			String serialized = objectMapper.writeValueAsString(arguments);
			byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
			return secretScanner.scan(bytes, serialized);
		} catch (Exception e) {
			log.warn("Error serializing tool arguments for guardrail scan: {}", e.getMessage());
			return SecretScanResult.clean();
		}
	}

	/**
	 * Wraps tool output content in a secure, nonced XML delimiter block to defend against indirect prompt injection.
	 *
	 * @param toolName namespaced tool name
	 * @param rawText  raw output string produced by the tool
	 * @return nonced delimiter block
	 */
	public String wrapToolOutputWithNonce(String toolName, String rawText) {
		byte[] nonceBytes = new byte[8];
		RANDOM.nextBytes(nonceBytes);
		String nonce = HexFormat.of().formatHex(nonceBytes);

		String safeText = rawText == null ? "" : rawText;
		return "<tool_result name=\"" + toolName + "\" nonce=\"" + nonce + "\" context=\"EXTERNAL_UNTRUSTED_DATA\">\n"
				+ safeText + "\n"
				+ "</tool_result>";
	}

	/**
	 * Inspects tool execution output text for indirect prompt injection attack markers.
	 *
	 * @param outputText tool output content
	 * @return true if adversarial injection patterns are detected
	 */
	public boolean containsIndirectPromptInjection(@Nullable String outputText) {
		if (outputText == null || outputText.isBlank()) {
			return false;
		}
		return INDIRECT_INJECTION_MARKERS.matcher(outputText).find();
	}
}
