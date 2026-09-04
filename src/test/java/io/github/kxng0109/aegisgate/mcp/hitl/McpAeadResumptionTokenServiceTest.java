package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP AEAD Resumption Token Service Unit Tests")
class McpAeadResumptionTokenServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpAeadResumptionTokenService tokenService;

	@BeforeEach
	void setUp() {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("super-secret-hitl-key-32-bytes!!"));
		tokenService = new McpAeadResumptionTokenService(properties, objectMapper);
	}

	@Test
	@DisplayName("Mints and successfully verifies valid AEAD resumption token")
	void mintAndVerifyValidToken() {
		String argsJson = "{\"sql\": \"SELECT * FROM users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-101",
				"tenant-corp",
				"postgres__run_query",
				argsSha,
				now,
				now.plusSeconds(300)
		);

		String mintedToken = tokenService.mintToken(claims);
		assertThat(mintedToken).startsWith("v2.aead.");

		Optional<McpResumptionClaims> extracted = tokenService.verifyAndExtract(
				mintedToken,
				argsSha,
				"tenant-corp"
		);

		assertThat(extracted).isPresent();
		assertThat(extracted.get().tokenId()).isEqualTo("tok-101");
		assertThat(extracted.get().toolName()).isEqualTo("postgres__run_query");
		assertThat(extracted.get().ownerId()).isEqualTo("tenant-corp");
	}

	@Test
	@DisplayName("Rejects token on AEAD tag tampering or corrupted ciphertext")
	void rejectsTamperedToken() {
		String argsJson = "{\"sql\": \"SELECT 1\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-tamper",
				"tenant-corp",
				"postgres__query",
				argsSha,
				now,
				now.plusSeconds(300)
		);

		String minted = tokenService.mintToken(claims);
		// Corrupt a byte in the middle of the ciphertext payload
		int tamperIdx = "v2.aead.".length() + 10;
		char origChar = minted.charAt(tamperIdx);
		char corruptedChar = origChar == 'A' ? 'B' : 'A';
		String tampered = minted.substring(0, tamperIdx) + corruptedChar + minted.substring(tamperIdx + 1);

		assertThat(tokenService.verifyAndExtract(tampered, argsSha, "tenant-corp")).isEmpty();
	}

	@Test
	@DisplayName("Rejects resumption when tool arguments were modified post-suspension")
	void rejectsArgumentTampering() {
		String originalArgs = "{\"sql\": \"SELECT id FROM users\"}";
		String originalSha = McpAeadResumptionTokenService.computeArgsSha256(originalArgs);

		String tamperedArgs = "{\"sql\": \"DROP TABLE users\"}";
		String tamperedSha = McpAeadResumptionTokenService.computeArgsSha256(tamperedArgs);

		Instant now = Instant.now();
		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-arg-tamper",
				"tenant-corp",
				"postgres__query",
				originalSha,
				now,
				now.plusSeconds(300)
		);

		String token = tokenService.mintToken(claims);

		// Attempt verification with modified argument hash
		assertThat(tokenService.verifyAndExtract(token, tamperedSha, "tenant-corp")).isEmpty();
	}

	@Test
	@DisplayName("Rejects resumption when caller tenant owner does not match token binding")
	void rejectsOwnerMismatch() {
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256("{}");
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-owner",
				"tenant-alpha",
				"postgres__query",
				argsSha,
				now,
				now.plusSeconds(300)
		);

		String token = tokenService.mintToken(claims);

		// Attacker tenant-bravo attempts to use tenant-alpha's token
		assertThat(tokenService.verifyAndExtract(token, argsSha, "tenant-bravo")).isEmpty();
	}

	@Test
	@DisplayName("Rejects expired resumption tokens")
	void rejectsExpiredTokens() {
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256("{}");
		Instant now = Instant.now();

		McpResumptionClaims expiredClaims = new McpResumptionClaims(
				"tok-expired",
				"tenant-corp",
				"postgres__query",
				argsSha,
				now.minusSeconds(600),
				now.minusSeconds(10)
		);

		String token = tokenService.mintToken(expiredClaims);
		assertThat(tokenService.verifyAndExtract(token, argsSha, "tenant-corp")).isEmpty();

		// computeArgsSha256 with null
		assertThat(McpAeadResumptionTokenService.computeArgsSha256(null)).isNotBlank();
	}
}
