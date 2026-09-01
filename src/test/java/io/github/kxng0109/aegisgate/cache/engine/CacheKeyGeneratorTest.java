package io.github.kxng0109.aegisgate.cache.engine;

import io.github.kxng0109.aegisgate.cache.contracts.CacheScope;
import io.github.kxng0109.aegisgate.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.aegisgate.proxy.protocol.OpenAiChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheKeyGenerator")
class CacheKeyGeneratorTest {

	private final CacheKeyGenerator generator = new CacheKeyGenerator();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("extractUserPrompt extracts text from single-turn and multi-turn message arrays")
	void extractUserPrompt() {
		OpenAiChatRequest singleTurn = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("You are helpful")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("What is 2+2?"))
				),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractUserPrompt(singleTurn)).isEqualTo("What is 2+2?");

		OpenAiChatRequest multiTurn = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("First question")),
						new OpenAiChatRequest.Message("assistant", objectMapper.valueToTree("First answer")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Second question"))
				),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractUserPrompt(multiTurn)).isEqualTo("Second question");

		OpenAiChatRequest empty = new OpenAiChatRequest(
				"gpt-4o", List.of(), 0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractUserPrompt(empty)).isEmpty();
	}

	@Test
	@DisplayName("extractText supports multipart array content nodes")
	void extractMultipartText() {
		ArrayNode parts = objectMapper.createArrayNode();
		parts.add("Part 1 ");
		ObjectNode textObj = objectMapper.createObjectNode();
		textObj.put("type", "text");
		textObj.put("text", "Part 2");
		parts.add(textObj);

		OpenAiChatRequest req = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", parts)),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractUserPrompt(req)).isEqualTo("Part 1 Part 2");
	}

	@Test
	@DisplayName("extractSystemPrompt extracts and concatenates system and developer roles")
	void extractSystemPrompt() {
		OpenAiChatRequest req = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System instruction 1")),
						new OpenAiChatRequest.Message("developer", objectMapper.valueToTree("Developer rule 2")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))
				),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractSystemPrompt(req)).isEqualTo("System instruction 1\nDeveloper rule 2");
	}

	@Test
	@DisplayName("computePrefixHash returns empty for single-turn and deterministic hash for multi-turn")
	void computePrefixHash() {
		OpenAiChatRequest singleTurn = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))
				),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.computePrefixHash(singleTurn, 4)).isEmpty();

		OpenAiChatRequest multiTurnA = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Question 1")),
						new OpenAiChatRequest.Message("assistant", objectMapper.valueToTree("Answer 1")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Question 2"))
				),
				0.0, null, null, null, null, true, null
		);
		String hashA = generator.computePrefixHash(multiTurnA, 4);
		assertThat(hashA).isNotEmpty();

		// Same history prefix produces identical hash
		OpenAiChatRequest multiTurnB = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("System")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Question 1")),
						new OpenAiChatRequest.Message("assistant", objectMapper.valueToTree("Answer 1")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("A different Question 2"))
				),
				0.0, null, null, null, null, true, null
		);
		String hashB = generator.computePrefixHash(multiTurnB, 4);
		assertThat(hashB).isEqualTo(hashA);
	}

	@Test
	@DisplayName("extractText handles null, nullNode, array of non-text, and object nodes")
	void extractTextEdgeCases() {
		assertThat(generator.extractText(null)).isEmpty();
		assertThat(generator.extractText(objectMapper.nullNode())).isEmpty();

		ArrayNode arr = objectMapper.createArrayNode();
		arr.add(123);
		ObjectNode emptyObj = objectMapper.createObjectNode();
		emptyObj.put("other", "value");
		arr.add(emptyObj);
		assertThat(generator.extractText(arr)).isEmpty();

		ObjectNode obj = objectMapper.createObjectNode();
		obj.put("custom", 42);
		assertThat(generator.extractText(obj)).contains("42");
	}

	@Test
	@DisplayName("extractSystemPrompt handles empty, null role, and blank text")
	void extractSystemPromptEdgeCases() {
		OpenAiChatRequest req = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message(null, objectMapper.valueToTree("no role")),
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("   ")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("hi"))
				),
				null, null, null, null, null, true, null
		);
		assertThat(generator.extractSystemPrompt(req)).isEmpty();
	}

	@Test
	@DisplayName("comprehensive branch coverage for null roles, empty lists, and various message formats")
	void allBranchVariations() {
		// 1. Null role in prefix hashing and exact hashing
		OpenAiChatRequest reqNullRoles = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message(null, objectMapper.valueToTree("system-like")),
						new OpenAiChatRequest.Message("assistant", objectMapper.valueToTree("reply")),
						new OpenAiChatRequest.Message(null, objectMapper.valueToTree("trailing user"))
				),
				null, null, null, null, null, true, null
		);
		String prefix = generator.computePrefixHash(reqNullRoles, 2);
		assertThat(prefix).isEmpty();

		OpenAiChatRequest multiNullRole = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Q1")),
						new OpenAiChatRequest.Message(null, objectMapper.valueToTree("A1")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Q2"))
				),
				null, null, null, null, null, true, null
		);
		String multiPrefix = generator.computePrefixHash(multiNullRole, 2);
		assertThat(multiPrefix).isNotEmpty();

		// 2. generateKey with GLOBAL and TENANT and USER with non-null userId
		CompoundCacheKey global = generator.generateKey(multiNullRole, "tenant1", CacheScope.GLOBAL, null, 2);
		assertThat(global.ownerId()).isEqualTo("global");

		CompoundCacheKey user = generator.generateKey(multiNullRole, "tenant1", CacheScope.USER, "u1", 2);
		assertThat(user.ownerId()).isEqualTo("tenant1:u1");

		CompoundCacheKey tenant = generator.generateKey(multiNullRole, "tenant1", CacheScope.TENANT, null, 2);
		assertThat(tenant.ownerId()).isEqualTo("tenant1");

		// 3. System prompt with multiple entries and developer role
		OpenAiChatRequest multiSys = new OpenAiChatRequest(
				"gpt-4o",
				List.of(
						new OpenAiChatRequest.Message("system", objectMapper.valueToTree("Sys1")),
						new OpenAiChatRequest.Message("developer", objectMapper.valueToTree("Dev2")),
						new OpenAiChatRequest.Message("developer", objectMapper.valueToTree("Dev3")),
						new OpenAiChatRequest.Message("user", objectMapper.valueToTree("U1"))
				),
				0.2, 50, null, 0.95, null, true, null
		);
		String sysPrompt = generator.extractSystemPrompt(multiSys);
		assertThat(sysPrompt).isEqualTo("Sys1\nDev2\nDev3");
		assertThat(generator.computePrefixHash(multiSys, 2)).isEmpty();

		// 4. Object without text field or text is not textual
		ArrayNode arrSpecial = objectMapper.createArrayNode();
		ObjectNode noTextObj = objectMapper.createObjectNode();
		noTextObj.put("otherField", "val");
		arrSpecial.add(noTextObj);
		ObjectNode nonTextVal = objectMapper.createObjectNode();
		nonTextVal.put("text", 123);
		arrSpecial.add(nonTextVal);
		OpenAiChatRequest reqSpecial = new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", arrSpecial)),
				0.0, null, null, null, null, true, null
		);
		assertThat(generator.extractUserPrompt(reqSpecial)).isEmpty();
	}
}
