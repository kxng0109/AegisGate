package io.github.kxng0109.aegisgate.mcp.hitl;

import io.github.kxng0109.aegisgate.config.SensitiveString;
import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("McpAeadResumptionTokenService PBKDF2 key derivation")
class McpAeadPbkdf2Test {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SECRET_A = "test-only-hitl-secret-32-bytes-minimum!!";
	private static final String SECRET_B = "a-different-32-byte-minimum-secret-!!";

	private static McpAeadResumptionTokenService serviceFor(String secret) {
		McpGatewayProperties props = new McpGatewayProperties();
		props.setHitlSecret(new SensitiveString(secret));
		return new McpAeadResumptionTokenService(props, MAPPER);
	}

	private static McpResumptionClaims claims(String owner) {
		String tokenId = UUID.randomUUID().toString().replace("-", "");
		Instant now = Instant.now();
		return new McpResumptionClaims(
				tokenId, owner, "ns.tool",
				McpAeadResumptionTokenService.computeArgsSha256("{}"), now, now.plusSeconds(300)
		);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("construction with 600K PBKDF2 iterations completes within CI budget")
	void constructionCompletesInCiBudget() {
		assertThat(serviceFor(SECRET_A)).isNotNull();
	}

	@Test
	@DisplayName("same secret verifies across independent instances (determinism)")
	void determinismAcrossInstances() {
		McpAeadResumptionTokenService first = serviceFor(SECRET_A);
		McpAeadResumptionTokenService second = serviceFor(SECRET_A);
		McpResumptionClaims original = claims("owner-1");
		String token = first.mintToken(original);
		Optional<McpResumptionClaims> back =
				second.verifyAndExtract(token, original.argsSha256(), "owner-1");
		assertThat(back).isPresent();
		assertThat(back.get().tokenId()).isEqualTo(original.tokenId());
	}

	@Test
	@DisplayName("different secret, tampered args, wrong owner, and corrupt tokens are rejected")
	void avalancheRejectsForeignTokens() {
		McpAeadResumptionTokenService first = serviceFor(SECRET_A);
		McpAeadResumptionTokenService second = serviceFor(SECRET_B);
		McpResumptionClaims original = claims("owner-1");
		String tokenFromFirst = first.mintToken(original);
		assertThat(second.verifyAndExtract(tokenFromFirst, original.argsSha256(), "owner-1")).isEmpty();
		assertThat(first.verifyAndExtract(tokenFromFirst, "0".repeat(64), "owner-1")).isEmpty();
		assertThat(first.verifyAndExtract(tokenFromFirst, original.argsSha256(), "other-owner")).isEmpty();
		assertThat(first.verifyAndExtract(tokenFromFirst + "x", original.argsSha256(), "owner-1")).isEmpty();
		assertThat(first.verifyAndExtract(null, original.argsSha256(), "owner-1")).isEmpty();
		assertThat(first.verifyAndExtract("garbage", original.argsSha256(), "owner-1")).isEmpty();
	}

	@Test
	@DisplayName("PBEKeySpec clear-then-read throws (memory-wipe contract)")
	void pbeKeySpecClearContract() {
		char[] password = "secret".toCharArray();
		PBEKeySpec spec = new PBEKeySpec(
				password,
				"AegisGate-MCP-HITL-v1".getBytes(StandardCharsets.UTF_8), 600_000, 256
		);
		spec.clearPassword();
		assertThatThrownBy(spec::getPassword).isInstanceOf(IllegalStateException.class);
	}
}
