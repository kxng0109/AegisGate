package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin DTO Tests")
@SuppressWarnings("DataFlowIssue")
class AdminDtoTest {

	@Test
	@DisplayName("CreateKeyRequest handles null optional fields with safe defaults")
	void createKeyRequestNullDefaults() {
		CreateKeyRequest req1 = new CreateKeyRequest("owner-1", "name-1", null, null, null, null);
		assertThat(req1.rpmLimit()).isZero();
		assertThat(req1.tpmLimit()).isZero();
		assertThat(req1.allowedModels()).isEmpty();
		assertThat(req1.allowedProviders()).isEmpty();

		CreateKeyRequest req2 = new CreateKeyRequest("owner-2", "name-2", 10, 20, Set.of("m1"), Set.of("p1"));
		assertThat(req2.rpmLimit()).isEqualTo(10);
		assertThat(req2.tpmLimit()).isEqualTo(20);
		assertThat(req2.allowedModels()).containsExactly("m1");
		assertThat(req2.allowedProviders()).containsExactly("p1");
	}

	@Test
	@DisplayName("DTO accessors return expected values")
	void dtoAccessors() {
		Instant now = Instant.now();
		CreatedKeyResponse created = new CreatedKeyResponse(
				"id-1", "gw-plain", "gw-", "owner-1", "key1", 10, 20, Set.of("m1"), Set.of("p1"), true, now
		);
		assertThat(created.keyId()).isEqualTo("id-1");
		assertThat(created.key()).isEqualTo("gw-plain");
		assertThat(created.keyPrefix()).isEqualTo("gw-");
		assertThat(created.ownerId()).isEqualTo("owner-1");
		assertThat(created.name()).isEqualTo("key1");
		assertThat(created.rpmLimit()).isEqualTo(10);
		assertThat(created.tpmLimit()).isEqualTo(20);
		assertThat(created.allowedModels()).containsExactly("m1");
		assertThat(created.allowedProviders()).containsExactly("p1");
		assertThat(created.enabled()).isTrue();
		assertThat(created.createdAt()).isEqualTo(now);

		KeyResponse keyResp = new KeyResponse(
				"id-1", "gw-", "owner-1", "key1", 10, 20, Set.of("m1"), Set.of("p1"), true, now
		);
		assertThat(keyResp.keyId()).isEqualTo("id-1");

		UpdateKeyRequest updateReq = new UpdateKeyRequest("name-2", 30, 40, Set.of("m2"), Set.of("p2"), false);
		assertThat(updateReq.name()).isEqualTo("name-2");
		assertThat(updateReq.rpmLimit()).isEqualTo(30);
		assertThat(updateReq.tpmLimit()).isEqualTo(40);
		assertThat(updateReq.allowedModels()).containsExactly("m2");
		assertThat(updateReq.allowedProviders()).containsExactly("p2");
		assertThat(updateReq.enabled()).isFalse();

		CircuitStateResponse circuitResp = new CircuitStateResponse("openai", "CLOSED");
		assertThat(circuitResp.provider()).isEqualTo("openai");
		assertThat(circuitResp.state()).isEqualTo("CLOSED");

		ProblemDetailResponse problem = ProblemDetailResponse.of(
				"Bad Request",
				400,
				"detail message",
				"/v1/admin/keys"
		);
		assertThat(problem.title()).isEqualTo("Bad Request");
		assertThat(problem.status()).isEqualTo(400);
		assertThat(problem.detail()).isEqualTo("detail message");
		assertThat(problem.instance()).isEqualTo("/v1/admin/keys");
		assertThat(problem.type()).isEqualTo("https://aegisgate.io/errors/400");
		assertThat(problem.timestamp()).isNotNull();
	}
}
