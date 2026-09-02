package io.github.kxng0109.aegisgate.cache;

import io.github.kxng0109.aegisgate.cache.config.AegisCacheProperties;
import io.github.kxng0109.aegisgate.cache.contracts.CacheEntry;
import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.cache.engine.CacheGuardrails;
import io.github.kxng0109.aegisgate.cache.engine.CacheKeyGenerator;
import io.github.kxng0109.aegisgate.cache.engine.CachePolicyEngine;
import io.github.kxng0109.aegisgate.cache.engine.SingleFlightManager;
import io.github.kxng0109.aegisgate.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.aegisgate.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.aegisgate.cache.engine.l2.VectorSearchResult;
import io.github.kxng0109.aegisgate.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.aegisgate.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("CacheFullCoverageTest")
@SuppressWarnings("DataFlowIssue")
class CacheFullCoverageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CacheKeyGenerator keyGenerator = new CacheKeyGenerator();
	private final CacheGuardrails guardrails = new CacheGuardrails();
	private final AegisCacheProperties properties = new AegisCacheProperties();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);
	private final SingleFlightManager singleFlight = new SingleFlightManager();

	@Test
	@DisplayName("CacheKeyGenerator covers fallback non-user last message and all parameter branches")
	void keyGeneratorBranches() {
		// 1. Message list with only assistant message -> triggers line 43 fallback
		OpenAiChatRequest assistantOnly = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("assistant", objectMapper.valueToTree("Assistant response"))),
				null, null, null, null, null, true, null
		);
		assertThat(keyGenerator.extractUserPrompt(assistantOnly)).isEqualTo("Assistant response");

		// 2. USER scope with null userId
		CompoundCacheKey userNullId = keyGenerator.generateKey(assistantOnly, "tenant1", CacheScope.USER, null, 2);
		assertThat(userNullId.ownerId()).isEqualTo("tenant1");

		// 3. System prompt non-blank with top_p and max_tokens non-null
		OpenAiChatRequest fullReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System rules")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("User query"))
				),
				0.05, 500, null, 0.95, null, true, null
		);
		CompoundCacheKey fullKey = keyGenerator.generateKey(fullReq, "tenant1", CacheScope.TENANT, null, 2);
		assertThat(fullKey.systemPromptHash()).isNotEmpty();
		assertThat(fullKey.exactHash()).isNotEmpty();
	}

	@Test
	@DisplayName("CachePolicyEngine covers all Cache-Control, Mode, and Temperature branches")
	void policyEngineBranches() {
		OpenAiChatRequest reqLowTemp = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.05,
				null,
				null,
				null,
				null,
				true,
				null
		);

		// Cache-Control: public (contains neither no-store nor no-cache)
		MockHttpServletRequest reqPublic = new MockHttpServletRequest();
		reqPublic.addHeader("Cache-Control", "public, max-age=3600");
		assertThat(policyEngine.shouldEvaluateCache(reqLowTemp, reqPublic)).isTrue();
		assertThat(policyEngine.shouldStoreInCache(reqLowTemp, reqPublic)).isTrue();

		// Cache-Control: no-cache alone
		MockHttpServletRequest reqNoCache = new MockHttpServletRequest();
		reqNoCache.addHeader("Cache-Control", "no-cache");
		assertThat(policyEngine.shouldEvaluateCache(reqLowTemp, reqNoCache)).isFalse();

		// X-Aegis-Cache-Mode: bypass
		MockHttpServletRequest reqBypass = new MockHttpServletRequest();
		reqBypass.addHeader("X-Aegis-Cache-Mode", "bypass");
		assertThat(policyEngine.shouldEvaluateCache(reqLowTemp, reqBypass)).isFalse();

		// X-Aegis-Cache-Mode: write-only
		MockHttpServletRequest reqWriteOnly = new MockHttpServletRequest();
		reqWriteOnly.addHeader("X-Aegis-Cache-Mode", "write-only");
		assertThat(policyEngine.shouldEvaluateCache(reqLowTemp, reqWriteOnly)).isFalse();

		// Temperature below floor (0.05 <= 0.10)
		assertThat(policyEngine.shouldEvaluateCache(reqLowTemp, reqPublic)).isTrue();
	}

	@Test
	@DisplayName("CacheGuardrails covers matching positive polarity pair")
	void guardrailsPositivePair() {
		// Both have positive polarity term "enable"
		assertThat(guardrails.checkPolarityMatch("enable 2FA now", "enable two factor authentication")).isTrue();
		// Both have negative polarity term "disable"
		assertThat(guardrails.checkPolarityMatch("disable 2FA now", "disable two factor authentication")).isTrue();
	}

	@Test
	@DisplayName("RedisExactCache covers empty string return from Redis")
	void redisExactCacheEmptyString() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(ops);

		CompoundCacheKey key = new CompoundCacheKey("t1", CacheScope.TENANT, "m", "h", "", "", "p");
		when(ops.get(key.toExactRedisKey())).thenReturn("   ");

		RedisExactCache cache = new RedisExactCache(redisTemplate, objectMapper, properties);
		assertThat(cache.get(key)).isNull();
	}

	@Test
	@DisplayName("RedisSemanticVectorCache and RediSearchVectorClient full branch coverage")
	void semanticCacheAndClientBranches() {
		RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
		EmbeddingService embeddingService = mock(EmbeddingService.class);
		RedisSemanticVectorCache semanticCache = new RedisSemanticVectorCache(
				vectorClient, embeddingService, guardrails, properties
		);

		// 1. storeSemanticEntry when disabled or blank prompt
		properties.getSemantic().setEnabled(false);
		CompoundCacheKey key = new CompoundCacheKey("t1", CacheScope.TENANT, "m", "h", "", "", "prompt");
		semanticCache.storeSemanticEntry(key, "{}", 1, 1, 2, Duration.ofHours(1));

		properties.getSemantic().setEnabled(true);
		CompoundCacheKey blankKey = new CompoundCacheKey("t1", CacheScope.TENANT, "m", "h", "", "", "   ");
		semanticCache.storeSemanticEntry(blankKey, "{}", 1, 1, 2, Duration.ofHours(1));

		// 2. generateEmbedding when response.data() is empty or null, or returns 0-length vector
		when(embeddingService.processEmbedding(any(), eq("t1"))).thenReturn(
				new EmbeddingResponse("list", List.of(), "m", null)
		);
		assertThat(semanticCache.findSemanticMatch(key)).isNull();

		when(embeddingService.processEmbedding(any(), eq("t1"))).thenReturn(
				new EmbeddingResponse("list", null, "m", null)
		);
		assertThat(semanticCache.findSemanticMatch(key)).isNull();

		when(embeddingService.processEmbedding(any(), eq("t1"))).thenReturn(
				new EmbeddingResponse(
						"list",
						List.of(new EmbeddingData(
								"embedding",
								0,
								new float[0]
						)),
						"m",
						null
				)
		);
		assertThat(semanticCache.findSemanticMatch(key)).isNull();
		semanticCache.storeSemanticEntry(key, "{}", 1, 1, 2, Duration.ofHours(1));

		// 3. Unsupported embedding object type
		when(embeddingService.processEmbedding(any(), eq("t1"))).thenReturn(
				new EmbeddingResponse(
						"list",
						List.of(new EmbeddingData(
								"embedding",
								0,
								12345
						)),
						"m", null
				)
		);
		assertThat(semanticCache.findSemanticMatch(key)).isNull();

		// 4. RediSearchVectorClient string conversions and odd list boundaries
		RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
		RedisConnection conn = mock(RedisConnection.class);
		when(factory.getConnection()).thenReturn(conn);
		RediSearchVectorClient client = new RediSearchVectorClient(factory);

		// BUSYKEY and already exists exception on create, plus null message exception
		when(conn.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(new RuntimeException(
				"BUSYKEY Target already exists"));
		assertThat(client.createIndexIfNotExists("idx", "doc:", 1536)).isFalse();

		when(conn.execute(eq("FT.CREATE"), any(byte[][].class))).thenThrow(new RuntimeException((String) null));
		assertThat(client.createIndexIfNotExists("idx", "doc:", 1536)).isFalse();

		// Trailing odd doc key without attributes
		List<Object> oddDocList = List.of(1L, "docKeyWithoutAttrs".getBytes());
		when(conn.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(oddDocList);
		assertThat(client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1)).isEmpty();

		// parseSearchResults with non-byte-array doc key
		List<Object> nonByteDocList = List.of(1L, 99999, List.of("score".getBytes(), "0.01".getBytes()));
		when(conn.execute(eq("FT.SEARCH"), any(byte[][].class))).thenReturn(nonByteDocList);
		List<VectorSearchResult> parsedNonBytes = client.searchKnn("idx", "@tag:{1}", new float[]{0.1f}, 1);
		assertThat(parsedNonBytes).hasSize(1);
		assertThat(parsedNonBytes.getFirst().docKey()).isEqualTo("99999");

		// parseInt branches in RedisSemanticVectorCache
		VectorSearchResult matchWithNullFields = new VectorSearchResult(
				"doc1", 0.01,
				Map.of("prompt_text", "p") // prompt_tokens and completion_tokens are null
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(1))).thenReturn(List.of(matchWithNullFields));
		when(embeddingService.processEmbedding(any(), eq("t1"))).thenReturn(
				new EmbeddingResponse(
						"list",
						List.of(new EmbeddingData(
								"embedding",
								0,
								new float[]{0.1f}
						)),
						"m",
						null
				)
		);
		CacheEntry nullTokensEntry = semanticCache.findSemanticMatch(key);
		assertThat(nullTokensEntry).isNotNull();
		assertThat(nullTokensEntry.promptTokens()).isZero();
	}
}
