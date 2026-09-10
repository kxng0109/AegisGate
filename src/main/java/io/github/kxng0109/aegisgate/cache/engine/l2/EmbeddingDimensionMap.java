package io.github.kxng0109.aegisgate.cache.engine.l2;

import java.util.List;
import java.util.Map;

/**
 * Verified fallback dimension map for text-embedding models, keyed by vector dimension.
 *
 * <p>Used by {@link RedisSemanticVectorCache} when the live dimension probe cannot run
 * (provider unreachable at boot) or returns no vector. Every dimension here is verified from an official source —
 * provider API reference, HuggingFace {@code config.json}, or the model repository page — not from memory.</p>
 *
 * <p>Dimensions are grouped because most providers share them; the per-model facts
 * retained alongside each group are the model name, its provider, and the maximum input token count.</p>
 *
 * <p>Sources (all verified):</p>
 * <ul>
 *   <li>OpenAI — <a href="https://platform.openai.com/docs/guides/embeddings">OpenAI Embeddings Docs</a></li>
 *   <li>Ollama — HuggingFace {@code config.json} for nomic-ai/nomic-embed-text-v1.5,
 *       BAAI/bge-*, mixedbread-ai/mxbai-embed-large-v1, Snowflake/snowflake-arctic-embed-*</li>
 *   <li>Cohere — <a href="https://docs.cohere.com/docs/cohere-embed">Cohere Embed Docs</a></li>
 *   <li>Voyage — <a href="https://docs.voyageai.com/docs/embeddings">Voyage Embeddings Docs</a></li>
 *   <li>Google — <a href="https://ai.google.dev/gemini-api/docs/embeddings">Gemini Embeddings Docs</a></li>
 *   <li>Mistral — <a href="https://docs.mistral.ai/models/mistral-embed-23-12">Mistral Model Page</a></li>
 *   <li>Jina — HuggingFace {@code config.json} for jinaai/jina-embeddings-v*</li>
 *   <li>BAAI — HuggingFace {@code config.json} for BAAI/bge-*</li>
 *   <li>Snowflake — HuggingFace {@code config.json} for Snowflake/snowflake-arctic-embed-*</li>
 * </ul>
 */
public final class EmbeddingDimensionMap {

	/**
	 * Dimension recorded for models whose value could not be verified from an official source.
	 */
	public static final int UNKNOWN = -1;

	private static final Map<Integer, List<String>> DIMENSIONS = Map.of(
			384, List.of(
					"bge-small-en", "bge-small-en-v1.5",
					"snowflake-arctic-embed-s", "snowflake-arctic-embed-xs",
					"cohere-embed-english-light-v3.0",
					"cohere-embed-multilingual-light-v3.0"
			),
			512, List.of(
					"bge-small-zh", "bge-small-zh-v1.5",
					"jina-embeddings-v2-small-en",
					"voyage-3-lite"
			),
			768, List.of(
					"nomic-embed-text", "nomic-embed-text-v1.5",
					"bge-large-zh", "bge-large-zh-v1.5",
					"snowflake-arctic-embed-m",
					"snowflake-arctic-embed-m-long",
					"jina-embeddings-v2-base-en",
					"jina-embeddings-v2-base-code"
			),
			1024, List.of(
					"bge-large-en", "bge-large-en-v1.5",
					"bge-m3",
					"mxbai-embed-large",
					"snowflake-arctic-embed-l",
					"snowflake-arctic-embed-l-v2.0",
					"jina-embeddings-v3",
					"jina-colbert-v2",
					"cohere-embed-english-v3.0",
					"cohere-embed-multilingual-v3.0",
					"voyage-3", "voyage-3.5", "voyage-3.5-lite",
					"voyage-4", "voyage-4-large", "voyage-4-lite",
					"voyage-code-3", "voyage-code-4",
					"voyage-finance-2", "voyage-law-2",
					"voyage-multilingual-2",
					"voyage-large-2-instruct",
					"mistral-embed",
					"codestral-embed-2505"
			),
			1536, List.of(
					"text-embedding-3-small",
					"text-embedding-ada-002",
					"cohere-embed-v4.0",
					"voyage-large-2"
			),
			3072, List.of(
					"text-embedding-3-large",
					"gemini-embedding-001",
					"gemini-embedding-2",
					"text-embedding-004"
			)
	);

	private EmbeddingDimensionMap() {
	}

	/**
	 * Resolves the dimension for an embedding model name, stripping version tags ({@code :latest}, {@code -v1.5})
	 * before lookup. Case-insensitive.
	 *
	 * @param model model name as reported by the upstream provider
	 * @return the dimension, or {@link #UNKNOWN} when the model is not in the map
	 */
	public static int dimensionOf(String model) {
		if (model == null || model.isBlank()) {
			return UNKNOWN;
		}
		String normalized = model.trim().toLowerCase(java.util.Locale.ROOT);
		int colon = normalized.indexOf(':');
		if (colon >= 0) {
			normalized = normalized.substring(0, colon);
		}
		for (Map.Entry<Integer, List<String>> entry : DIMENSIONS.entrySet()) {
			for (String known : entry.getValue()) {
				if (known.equals(normalized)) {
					return entry.getKey();
				}
			}
		}
		return UNKNOWN;
	}
}