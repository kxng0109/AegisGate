package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.CreateKeyRequest;
import io.github.kxng0109.aegisgate.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.KeyResponse;
import io.github.kxng0109.aegisgate.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.security.ratelimit.KeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin - Key Management", description = "Provisioning, updating, and revoking virtual API keys with granular RPM/TPM quotas")
public class AdminKeyController {

	private final KeyManagementService keyManagementService;

	/**
	 * Creates a new virtual API key and returns the single-exposure plaintext along with metadata.
	 *
	 * @param request parameters for the new key
	 * @return HTTP 201 Created with the created key details
	 */
	@Operation(
			summary = "Create virtual API key",
			description = "Generates a new 192-bit entropy virtual API key (prefixed with `gw-`). Returns the single-exposure plaintext key alongside metadata.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "201",
					description = "Virtual API key provisioned successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CreatedKeyResponse.class),
							examples = @ExampleObject(
									name = "Created Key Response",
									value = """
											{
											  "hash": "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
											  "plaintextKey": "gw-aB3_x9...32chars",
											  "keyPrefix": "gw-",
											  "ownerId": "tenant-corp",
											  "name": "production-key",
											  "rpmLimit": 120,
											  "tpmLimit": 500000,
											  "allowedModels": ["gpt-56-luna", "claude-sonnet-4-5"],
											  "allowedProviders": ["openai", "anthropic"],
											  "enabled": true,
											  "createdAt": "2026-09-01T12:00:00Z"
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "400", description = "Validation failure or invalid quota limits"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
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
	@Operation(
			summary = "List virtual API keys",
			description = "Retrieves metadata for all registered virtual keys (excluding plaintext secrets), optionally filtered by tenant owner ID.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "List of virtual keys retrieved",
					content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = KeyResponse.class)))
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<List<KeyResponse>> listKeys(
			@Parameter(description = "Optional tenant owner ID to filter keys", example = "tenant-corp")
			@RequestParam(value = "ownerId", required = false) String ownerId
	) {
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
	@Operation(
			summary = "Get virtual API key metadata",
			description = "Retrieves metadata for a specific virtual key using its SHA-256 hex hash digest.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Key metadata retrieved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = KeyResponse.class))),
			@ApiResponse(responseCode = "404", description = "Key not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/{hashHex}")
	public ResponseEntity<KeyResponse> getKey(
			@Parameter(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
			@PathVariable("hashHex") String hashHex
	) {
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
	@Operation(
			summary = "Update virtual API key",
			description = "Dynamically updates key quotas (RPM/TPM), allowed model and provider lists, or enables/disables the key.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Key updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = KeyResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid update payload"),
			@ApiResponse(responseCode = "404", description = "Key not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PatchMapping("/{hashHex}")
	public ResponseEntity<KeyResponse> updateKey(
			@Parameter(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
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
	 * Deletes a key by its hash.
	 *
	 * @param hashHex key hash hex string
	 * @return HTTP 204 No Content, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Delete virtual API key",
			description = "Permanently deletes a virtual API key and purges local memory caches.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Key deleted successfully"),
			@ApiResponse(responseCode = "404", description = "Key not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@DeleteMapping("/{hashHex}")
	public ResponseEntity<Void> deleteKey(
			@Parameter(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
			@PathVariable("hashHex") String hashHex
	) {
		SHA256Hash hash = parseHash(hashHex);
		boolean deleted = keyManagementService.deleteKey(hash);
		if (deleted) {
			return ResponseEntity.noContent().build();
		}
		return ResponseEntity.notFound().build();
	}

	private KeyResponse toKeyResponse(VirtualApiKey key) {
		return new KeyResponse(
				key.keyHash() != null ? key.keyHash().hex() : "",
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

	private SHA256Hash parseHash(String hex) {
		if (hex == null || hex.length() != 64 || !hex.matches("^[a-fA-F0-9]{64}$")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key hash format: " + hex);
		}
		return SHA256Hash.fromHex(hex);
	}
}
