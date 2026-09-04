package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CreateKeyRequest;
import io.github.kxng0109.aegisgate.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.KeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("AdminKeyController")
class AdminKeyControllerTest {

	private final KeyManagementService keyManagementService = mock(KeyManagementService.class);
	private final AdminKeyController controller = new AdminKeyController(keyManagementService);

	@Test
	@DisplayName("createKey returns 201 Created with single-exposure plaintext and metadata")
	void createKeySuccess() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secret12345");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"), true, Instant.now()
		);
		KeyManagementService.CreatedKey created = new KeyManagementService.CreatedKey(
				hash, "gw-secret12345", metadata
		);

		when(keyManagementService.createKey(
				eq("owner-1"), eq("test-key"), eq(60), eq(1000),
				eq(Set.of("gpt-4o")), eq(Set.of("openai"))
		)).thenReturn(created);

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "test-key", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai")
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().key()).isEqualTo("gw-secret12345");
		assertThat(response.getBody().keyId()).isEqualTo(hash.hex());
		assertThat(response.getBody().ownerId()).isEqualTo("owner-1");
		assertThat(response.getBody().rpmLimit()).isEqualTo(60);

		// Create key with allowedTools and deniedTools
		CreateKeyRequest requestWithTools = new CreateKeyRequest(
				"owner-1", "test-key-tools", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*")
		);
		VirtualApiKey keyWithTools = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key-tools", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				"owner-1", "test-key-tools", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*")
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretTools", keyWithTools));

		ResponseEntity<CreatedKeyResponse> responseTools = controller.createKey(requestWithTools);
		assertThat(responseTools.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(responseTools.getBody().allowedTools()).containsExactly("postgres__*");
		assertThat(responseTools.getBody().deniedTools()).containsExactly("*:delete_*");
	}

	@Test
	@DisplayName("listKeys returns list of safe key responses")
	void listKeysSuccess() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);

		when(keyManagementService.listKeys("owner-1")).thenReturn(List.of(key));

		ResponseEntity<List<KeyResponse>> response = controller.listKeys("owner-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().keyId()).isEqualTo(hash.hex());

		// List without owner filter
		VirtualApiKey keyNullHash = new VirtualApiKey(
				null, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);
		when(keyManagementService.listKeys(null)).thenReturn(List.of(key, keyNullHash));
		ResponseEntity<List<KeyResponse>> allResponse = controller.listKeys(null);
		assertThat(allResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(allResponse.getBody()).hasSize(2);
		assertThat(allResponse.getBody().get(1).keyId()).isEmpty();
	}

	@Test
	@DisplayName("getKey returns 200 OK when found, 404 when absent, 400 on invalid hex")
	void getKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);

		when(keyManagementService.findByHash(hash)).thenReturn(Optional.of(key));
		when(keyManagementService.findByHash(argThat(h -> h != null && !h.equals(hash)))).thenReturn(Optional.empty());

		// Found
		ResponseEntity<KeyResponse> found = controller.getKey(hash.hex());
		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody()).isNotNull();
		assertThat(found.getBody().keyId()).isEqualTo(hash.hex());

		// Not Found
		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		ResponseEntity<KeyResponse> notFound = controller.getKey(missingHash.hex());
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Invalid Hex Scenarios
		assertThatThrownBy(() -> controller.getKey(null))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.getKey("short-hex"))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.getKey("z".repeat(64)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	@DisplayName("updateKey returns 200 OK when updated, 404 when not found")
	void updateKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey updated = new VirtualApiKey(
				hash, "gw-", "owner-1", "renamed-key", 120, 2000,
				Set.of("claude-3-5"), Set.of("anthropic"), true, Instant.now()
		);

		when(keyManagementService.updateKey(
				eq(hash), eq("renamed-key"), eq(120), eq(2000),
				eq(Set.of("claude-3-5")), eq(Set.of("anthropic")), eq(true)
		)).thenReturn(Optional.of(updated));

		UpdateKeyRequest request = new UpdateKeyRequest(
				"renamed-key", 120, 2000,
				Set.of("claude-3-5"), Set.of("anthropic"), true
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().name()).isEqualTo("renamed-key");

		// Update with tools
		UpdateKeyRequest requestTools = new UpdateKeyRequest(
				"renamed-key-tools", 120, 2000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true
		);
		VirtualApiKey updatedWithTools = new VirtualApiKey(
				hash, "gw-", "owner-1", "renamed-key-tools", 120, 2000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true, Instant.now()
		);
		when(keyManagementService.updateKey(
				eq(hash), eq("renamed-key-tools"), eq(120), eq(2000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("postgres__*")), eq(Set.of("*:delete_*")),
				eq(true)
		)).thenReturn(Optional.of(updatedWithTools));

		ResponseEntity<KeyResponse> responseTools = controller.updateKey(hash.hex(), requestTools);
		assertThat(responseTools.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(responseTools.getBody().allowedTools()).containsExactly("postgres__*");

		// Not found
		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		ResponseEntity<KeyResponse> notFound = controller.updateKey(missingHash.hex(), request);
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("deleteKey returns 204 No Content when deleted, 404 when not found")
	void deleteKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		when(keyManagementService.deleteKey(hash)).thenReturn(true);

		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		when(keyManagementService.deleteKey(missingHash)).thenReturn(false);

		ResponseEntity<Void> deleted = controller.deleteKey(hash.hex());
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<Void> notFound = controller.deleteKey(missingHash.hex());
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
