package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CreateKeyRequest;
import io.github.kxng0109.aegisgate.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.KeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST controller for administrative management of virtual API keys under {@code /v1/admin/keys}.
 */
@RestController
@RequestMapping("/v1/admin/keys")
@RequiredArgsConstructor
public class AdminKeyController {

	private final KeyManagementService keyManagementService;

	/**
	 * Creates a new virtual API key and returns the single-exposure plaintext along with metadata.
	 *
	 * @param request parameters for the new key
	 * @return HTTP 201 Created with the created key details
	 */
	@PostMapping
	public ResponseEntity<CreatedKeyResponse> createKey(@Valid @RequestBody CreateKeyRequest request) {
		KeyManagementService.CreatedKey created = keyManagementService.createKey(
				request.ownerId(),
				request.name(),
				request.rpmLimit(),
				request.tpmLimit(),
				request.allowedModels(),
				request.allowedProviders()
		);
		CreatedKeyResponse response = new CreatedKeyResponse(
				created.hash().hex(),
				created.plaintextKey(),
				created.key().keyPrefix(),
				created.key().ownerId(),
				created.key().name(),
				created.key().rpmLimit(),
				created.key().tpmLimit(),
				created.key().allowedModels(),
				created.key().allowedProviders(),
				created.key().enabled(),
				created.key().createdAt()
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Lists registered virtual API keys, optionally filtered by owner ID.
	 *
	 * @param ownerId optional owner filter
	 * @return HTTP 200 OK with list of safe key representations
	 */
	@GetMapping
	public ResponseEntity<List<KeyResponse>> listKeys(@RequestParam(value = "ownerId", required = false) String ownerId) {
		List<VirtualApiKey> keys = keyManagementService.listKeys(ownerId);
		List<KeyResponse> response = keys.stream().map(this::toKeyResponse).toList();
		return ResponseEntity.ok(response);
	}

	/**
	 * Retrieves metadata for a specific key by its SHA-256 hex digest.
	 *
	 * @param hashHex key hash hex string
	 * @return HTTP 200 OK with key metadata, or HTTP 404 Not Found
	 */
	@GetMapping("/{hashHex}")
	public ResponseEntity<KeyResponse> getKey(@PathVariable("hashHex") String hashHex) {
		SHA256Hash hash = parseHash(hashHex);
		return keyManagementService.findByHash(hash)
		                           .map(this::toKeyResponse)
		                           .map(ResponseEntity::ok)
		                           .orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Updates properties of an existing key.
	 *
	 * @param hashHex key hash hex string
	 * @param request update payload
	 * @return HTTP 200 OK with updated metadata, or HTTP 404 Not Found
	 */
	@PatchMapping("/{hashHex}")
	public ResponseEntity<KeyResponse> updateKey(
			@PathVariable("hashHex") String hashHex,
			@Valid @RequestBody UpdateKeyRequest request
	) {
		SHA256Hash hash = parseHash(hashHex);
		return keyManagementService.updateKey(
				                           hash,
				                           request.name(),
				                           request.rpmLimit(),
				                           request.tpmLimit(),
				                           request.allowedModels(),
				                           request.allowedProviders(),
				                           request.enabled()
		                           )
		                           .map(this::toKeyResponse)
		                           .map(ResponseEntity::ok)
		                           .orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Permanently deletes a key.
	 *
	 * @param hashHex key hash hex string
	 * @return HTTP 204 No Content if deleted, or HTTP 404 Not Found
	 */
	@DeleteMapping("/{hashHex}")
	public ResponseEntity<Void> deleteKey(@PathVariable("hashHex") String hashHex) {
		SHA256Hash hash = parseHash(hashHex);
		boolean deleted = keyManagementService.deleteKey(hash);
		return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private SHA256Hash parseHash(String hex) {
		if (hex == null || hex.length() != 64 || !hex.matches("^[a-fA-F0-9]{64}$")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key hash format: " + hex);
		}
		return SHA256Hash.fromHex(hex);
	}

	private KeyResponse toKeyResponse(VirtualApiKey key) {
		return new KeyResponse(
				key.keyHash().hex(),
				key.keyPrefix(),
				key.ownerId(),
				key.name(),
				key.rpmLimit(),
				key.tpmLimit(),
				key.allowedModels(),
				key.allowedProviders(),
				key.enabled(),
				key.createdAt()
		);
	}
}
