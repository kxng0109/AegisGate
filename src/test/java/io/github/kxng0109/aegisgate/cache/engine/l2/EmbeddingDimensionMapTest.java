package io.github.kxng0109.aegisgate.cache.engine.l2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingDimensionMap")
class EmbeddingDimensionMapTest {

	@Test
	@DisplayName("resolves known Ollama models including version tags")
	void knownOllamaModels() {
		assertThat(EmbeddingDimensionMap.dimensionOf("nomic-embed-text")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("nomic-embed-text:latest")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("bge-large-en")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("bge-large-en-v1.5")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("bge-m3")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("mxbai-embed-large")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("snowflake-arctic-embed-l")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("snowflake-arctic-embed-m")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("snowflake-arctic-embed-m-long")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("snowflake-arctic-embed-s")).isEqualTo(384);
		assertThat(EmbeddingDimensionMap.dimensionOf("snowflake-arctic-embed-xs")).isEqualTo(384);
	}

	@Test
	@DisplayName("resolves known provider models")
	void knownProviderModels() {
		assertThat(EmbeddingDimensionMap.dimensionOf("text-embedding-3-small")).isEqualTo(1536);
		assertThat(EmbeddingDimensionMap.dimensionOf("text-embedding-3-large")).isEqualTo(3072);
		assertThat(EmbeddingDimensionMap.dimensionOf("text-embedding-ada-002")).isEqualTo(1536);
		assertThat(EmbeddingDimensionMap.dimensionOf("cohere-embed-english-v3.0")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("cohere-embed-v4.0")).isEqualTo(1536);
		assertThat(EmbeddingDimensionMap.dimensionOf("voyage-3")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("voyage-large-2")).isEqualTo(1536);
		assertThat(EmbeddingDimensionMap.dimensionOf("voyage-3-lite")).isEqualTo(512);
		assertThat(EmbeddingDimensionMap.dimensionOf("gemini-embedding-001")).isEqualTo(3072);
		assertThat(EmbeddingDimensionMap.dimensionOf("text-embedding-004")).isEqualTo(3072);
		assertThat(EmbeddingDimensionMap.dimensionOf("mistral-embed")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("jina-embeddings-v3")).isEqualTo(1024);
		assertThat(EmbeddingDimensionMap.dimensionOf("jina-embeddings-v2-base-en")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("bge-small-en-v1.5")).isEqualTo(384);
	}

	@Test
	@DisplayName("lookup is case-insensitive and trims surrounding whitespace")
	void normalization() {
		assertThat(EmbeddingDimensionMap.dimensionOf("NOMIC-EMBED-TEXT")).isEqualTo(768);
		assertThat(EmbeddingDimensionMap.dimensionOf("  text-embedding-3-small  ")).isEqualTo(1536);
		assertThat(EmbeddingDimensionMap.dimensionOf("bge-large-en-v1.5")).isEqualTo(1024);
	}

	@Test
	@DisplayName("unknown models fall through to UNKNOWN rather than being guessed")
	void unknownFallsThrough() {
		assertThat(EmbeddingDimensionMap.dimensionOf("some-unknown-model")).isEqualTo(EmbeddingDimensionMap.UNKNOWN);
		assertThat(EmbeddingDimensionMap.dimensionOf("qwen2.5-embedding-7b")).isEqualTo(EmbeddingDimensionMap.UNKNOWN);
		assertThat(EmbeddingDimensionMap.dimensionOf("text-embedding-v3")).isEqualTo(EmbeddingDimensionMap.UNKNOWN);
		assertThat(EmbeddingDimensionMap.dimensionOf("")).isEqualTo(EmbeddingDimensionMap.UNKNOWN);
		assertThat(EmbeddingDimensionMap.dimensionOf(null)).isEqualTo(EmbeddingDimensionMap.UNKNOWN);
	}
}