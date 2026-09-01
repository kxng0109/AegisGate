package io.github.kxng0109.aegisgate.cache.engine.streaming;

import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CachedStreamReconstitution")
class CachedStreamReconstitutionTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CachedStreamReconstitution reconstitution = new CachedStreamReconstitution(objectMapper);

	@Test
	@DisplayName("streamCachedResponse emits valid OpenAI SSE stream including role, content deltas, finish, usage, and DONE")
	void streamCachedResponse() throws Exception {
		String payload = "{\"id\":\"chatcmpl-123\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello world, this is a cached response!\"}}]}";
		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"prompt",
				"",
				"",
				payload,
				10,
				15,
				25,
				Instant.now(),
				1.0f
		);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		reconstitution.streamCachedResponse(entry, "gpt-4o", true, out);

		String sseOutput = out.toString(StandardCharsets.UTF_8);
		assertThat(sseOutput).contains("data: {\"id\":\"chatcmpl-cache-");
		assertThat(sseOutput).contains("\"role\":\"assistant\"");
		assertThat(sseOutput).contains("Hello world, this is a cached re");
		assertThat(sseOutput).contains("sponse!");
		assertThat(sseOutput).contains("\"finish_reason\":\"stop\"");
		assertThat(sseOutput).contains("\"prompt_tokens\":10");
		assertThat(sseOutput).contains("\"completion_tokens\":15");
		assertThat(sseOutput).contains("data: [DONE]");
	}

	@Test
	@DisplayName("extractCompletionContent extracts plain text from JSON shapes or passes raw strings through")
	void extractCompletionContent() {
		assertThat(reconstitution.extractCompletionContent(null)).isEmpty();
		assertThat(reconstitution.extractCompletionContent("   ")).isEmpty();

		String standardJson = "{\"choices\":[{\"message\":{\"content\":\"Answer 1\"}}]}";
		assertThat(reconstitution.extractCompletionContent(standardJson)).isEqualTo("Answer 1");

		String deltaJson = "{\"choices\":[{\"delta\":{\"content\":\"Delta 2\"}}]}";
		assertThat(reconstitution.extractCompletionContent(deltaJson)).isEqualTo("Delta 2");

		String textJson = "{\"choices\":[{\"text\":\"Text 3\"}]}";
		assertThat(reconstitution.extractCompletionContent(textJson)).isEqualTo("Text 3");

		String choiceWithoutContent = "{\"choices\":[{}]}";
		assertThat(reconstitution.extractCompletionContent(choiceWithoutContent)).isEqualTo(choiceWithoutContent);

		String topContentJson = "{\"content\":\"Top content\"}";
		assertThat(reconstitution.extractCompletionContent(topContentJson)).isEqualTo("Top content");

		// Choices not an array
		String choicesNotArray = "{\"choices\": \"not an array\"}";
		assertThat(reconstitution.extractCompletionContent(choicesNotArray)).isEqualTo(choicesNotArray);

		// Choice with empty message object
		String choiceEmptyMessage = "{\"choices\": [{\"message\": {}}]}";
		assertThat(reconstitution.extractCompletionContent(choiceEmptyMessage)).isEqualTo(choiceEmptyMessage);

		// Choice with empty delta object
		String choiceEmptyDelta = "{\"choices\": [{\"delta\": {}}]}";
		assertThat(reconstitution.extractCompletionContent(choiceEmptyDelta)).isEqualTo(choiceEmptyDelta);

		String rawText = "Plain text string";
		assertThat(reconstitution.extractCompletionContent(rawText)).isEqualTo("Plain text string");
	}

	@Test
	@DisplayName("buildContentDeltaChunkJson handles mapper serialization failure gracefully")
	void deltaChunkMapperFailure() throws Exception {
		ObjectMapper faultyMapper = mock(ObjectMapper.class);
		when(faultyMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Simulated json error"));
		CachedStreamReconstitution faultyReconstitution = new CachedStreamReconstitution(faultyMapper);

		CacheEntry entry = new CacheEntry(
				"id1", "tenant1", CacheScope.TENANT, "gpt-4o", "p", "", "", "raw text", 5, 5, 10, Instant.now(), 1.0f
		);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		faultyReconstitution.streamCachedResponse(entry, "gpt-4o", false, out);

		assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"delta\":{\"content\":\"\"}");
	}
}
