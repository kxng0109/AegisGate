package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the OpenAI-compatible {@code /v1/embeddings} endpoint.
 */
@RestController
@RequestMapping("/v1/embeddings")
@RequiredArgsConstructor
@Tag(name = "Proxy - Embeddings", description = "High-throughput vector embeddings gateway with auto-batching and Little-Endian IEEE 754 float32 encoding")
public class EmbeddingController {

	private final EmbeddingService embeddingService;

	/**
	 * Handles embedding generation requests across configured providers with automatic batching and normalization.
	 *
	 * @param request            client embedding request
	 * @param httpServletRequest servlet request used to retrieve authenticated owner context
	 * @return HTTP 200 OK with standardized OpenAI embedding response
	 */
	@Operation(
			summary = "Generate dense vector embeddings",
			description = """
					Accepts OpenAI-compatible vector embedding requests. Automatically partitions large batches exceeding
					upstream provider limits (e.g. Cohere max 96, Ollama max 32), dispatches sub-batches concurrently over
					Virtual Threads, and reassembles dense vector results with deterministic `0..N-1` index preservation.
					""",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Vector embeddings generated and normalized successfully",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EmbeddingResponse.class),
							examples = @ExampleObject(
									name = "Embedding Vector Response",
									value = """
											{
											  "object": "list",
											  "data": [
											    {
											      "object": "embedding",
											      "index": 0,
											      "embedding": [0.0023064255, -0.009327292, 0.015797347]
											    }
											  ],
											  "model": "text-embedding-3-small",
											  "usage": {
											    "prompt_tokens": 8,
											    "total_tokens": 8
											  }
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "400", description = "Empty input array, missing model parameter, or exceeding 2048 batch cap"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid Virtual API Key"),
			@ApiResponse(responseCode = "403", description = "Key is disabled or unauthorized for embedding model"),
			@ApiResponse(responseCode = "404", description = "Model alias not configured"),
			@ApiResponse(responseCode = "429", description = "Virtual API Key rate limit quota exceeded"),
			@ApiResponse(responseCode = "502", description = "Upstream embedding provider failure"),
			@ApiResponse(responseCode = "503", description = "Rate limit engine or provider unavailable")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<EmbeddingResponse> createEmbeddings(
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Vector embedding request specifying model alias, text input (single string or array), and optional encoding format",
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EmbeddingRequest.class),
							examples = @ExampleObject(
									name = "Standard Embedding Request",
									value = """
											{
											  "model": "text-embedding-3-small",
											  "input": ["First text to embed", "Second text to embed"],
											  "encoding_format": "float"
											}
											"""
							)
					)
			)
			@RequestBody EmbeddingRequest request,
			HttpServletRequest httpServletRequest
	) {
		@Nullable String ownerId = (String) httpServletRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE);
		EmbeddingResponse response = embeddingService.processEmbedding(request, ownerId);
		return ResponseEntity.ok(response);
	}
}

