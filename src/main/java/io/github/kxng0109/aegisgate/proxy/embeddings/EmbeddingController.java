package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
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
public class EmbeddingController {

	private final EmbeddingService embeddingService;

	/**
	 * Handles embedding generation requests across configured providers with automatic batching and normalization.
	 *
	 * @param request            client embedding request
	 * @param httpServletRequest servlet request used to retrieve authenticated owner context
	 * @return HTTP 200 OK with standardized OpenAI embedding response
	 */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<EmbeddingResponse> createEmbeddings(
			@RequestBody EmbeddingRequest request,
			HttpServletRequest httpServletRequest
	) {
		@Nullable String ownerId = (String) httpServletRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE);
		EmbeddingResponse response = embeddingService.processEmbedding(request, ownerId);
		return ResponseEntity.ok(response);
	}
}
