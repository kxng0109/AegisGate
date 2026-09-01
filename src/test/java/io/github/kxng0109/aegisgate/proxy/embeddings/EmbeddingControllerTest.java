package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.security.filter.KeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("EmbeddingController")
@SuppressWarnings("DataFlowIssue")
class EmbeddingControllerTest {

	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final EmbeddingController controller = new EmbeddingController(embeddingService);

	@Test
	@DisplayName("createEmbeddings extracts ownerId and delegates to EmbeddingService")
	void createEmbeddingsDelegates() {
		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		when(httpRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE)).thenReturn("tenant-alpha");

		EmbeddingRequest request = new EmbeddingRequest("input text", "text-embedding-3-small", null, null, null);
		EmbeddingResponse expected = EmbeddingResponse.of(
				"text-embedding-3-small", List.of(EmbeddingData.of(0, new float[]{0.1f})), 5
		);

		when(embeddingService.processEmbedding(request, "tenant-alpha")).thenReturn(expected);

		ResponseEntity<EmbeddingResponse> response = controller.createEmbeddings(request, httpRequest);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
		verify(embeddingService).processEmbedding(request, "tenant-alpha");
	}
}
