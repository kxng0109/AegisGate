package io.github.kxng0109.aegisgate.proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UniversalToolNormalizer")
@SuppressWarnings("DataFlowIssue")
class UniversalToolNormalizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("converts OpenAI tool definition to Anthropic input_schema format")
	void convertsOpenAiToAnthropicTools() throws Exception {
		String json = """
				[
				  {
				    "type": "function",
				    "function": {
				      "name": "get_weather",
				      "description": "Fetch weather",
				      "parameters": {
				        "type": "object",
				        "properties": {
				          "location": {"type": "string"}
				        },
				        "required": ["location"]
				      }
				    }
				  }
				]""";
		ArrayNode tools = (ArrayNode) objectMapper.readTree(json);
		ArrayNode anthropicTools = UniversalToolNormalizer.toAnthropicTools(tools, objectMapper);

		assertNotNull(anthropicTools);
		assertEquals(1, anthropicTools.size());
		JsonNode tool = anthropicTools.get(0);
		assertEquals("get_weather", tool.get("name").asString());
		assertEquals("Fetch weather", tool.get("description").asString());
		assertTrue(tool.has("input_schema"));
		assertEquals("object", tool.get("input_schema").get("type").asString());
		assertEquals("string", tool.get("input_schema").path("properties").path("location").path("type").asString());
	}

	@Test
	@DisplayName("converts OpenAI tool definition to Gemini UPPERCASE OpenAPI schema format")
	void convertsOpenAiToGeminiTools() throws Exception {
		String json = """
				[
				  {
				    "type": "function",
				    "function": {
				      "name": "lookup_cve",
				      "description": "Search CVE database",
				      "parameters": {
				        "type": "object",
				        "properties": {
				          "cve_id": {"type": "string"},
				          "severity": {"type": "integer"},
				          "score": {"type": "number"},
				          "verified": {"type": "boolean"},
				          "tags": {
				            "type": "array",
				            "items": {"type": "string"}
				          }
				        },
				        "required": ["cve_id"]
				      }
				    }
				  }
				]""";
		ArrayNode tools = (ArrayNode) objectMapper.readTree(json);
		ArrayNode geminiTools = UniversalToolNormalizer.toGeminiTools(tools, objectMapper);

		assertNotNull(geminiTools);
		assertEquals(1, geminiTools.size());
		JsonNode decls = geminiTools.get(0).get("functionDeclarations");
		assertNotNull(decls);
		assertEquals(1, decls.size());

		JsonNode fn = decls.get(0);
		assertEquals("lookup_cve", fn.get("name").asString());
		assertEquals("Search CVE database", fn.get("description").asString());

		JsonNode params = fn.get("parameters");
		assertEquals("OBJECT", params.get("type").asString());
		JsonNode props = params.get("properties");
		assertEquals("STRING", props.get("cve_id").get("type").asString());
		assertEquals("INTEGER", props.get("severity").get("type").asString());
		assertEquals("NUMBER", props.get("score").get("type").asString());
		assertEquals("BOOLEAN", props.get("verified").get("type").asString());
		assertEquals("ARRAY", props.get("tags").get("type").asString());
		assertEquals("STRING", props.get("tags").get("items").get("type").asString());
	}

	@Test
	@DisplayName("translates tool choices to Anthropic format")
	void translatesAnthropicToolChoices() throws Exception {
		JsonNode autoChoice = objectMapper.readTree("\"auto\"");
		ObjectNode resAuto = UniversalToolNormalizer.toAnthropicToolChoice(autoChoice, null, objectMapper);
		assertNotNull(resAuto);
		assertEquals("auto", resAuto.get("type").asString());

		JsonNode requiredChoice = objectMapper.readTree("\"required\"");
		ObjectNode resReq = UniversalToolNormalizer.toAnthropicToolChoice(requiredChoice, null, objectMapper);
		assertNotNull(resReq);
		assertEquals("any", resReq.get("type").asString());

		JsonNode specificChoice = objectMapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\"calc\"}}");
		ObjectNode resSpec = UniversalToolNormalizer.toAnthropicToolChoice(specificChoice, null, objectMapper);
		assertNotNull(resSpec);
		assertEquals("tool", resSpec.get("type").asString());
		assertEquals("calc", resSpec.get("name").asString());

		ObjectNode resDisabledParallel = UniversalToolNormalizer.toAnthropicToolChoice(autoChoice, false, objectMapper);
		assertNotNull(resDisabledParallel);
		assertTrue(resDisabledParallel.get("disable_parallel_tool_use").asBoolean());
	}

	@Test
	@DisplayName("translates tool choices to Gemini toolConfig format")
	void translatesGeminiToolChoices() throws Exception {
		JsonNode autoChoice = objectMapper.readTree("\"auto\"");
		ObjectNode resAuto = UniversalToolNormalizer.toGeminiToolConfig(autoChoice, objectMapper);
		assertNotNull(resAuto);
		assertEquals("AUTO", resAuto.path("functionCallingConfig").path("mode").asString());

		JsonNode requiredChoice = objectMapper.readTree("\"required\"");
		ObjectNode resReq = UniversalToolNormalizer.toGeminiToolConfig(requiredChoice, objectMapper);
		assertNotNull(resReq);
		assertEquals("ANY", resReq.path("functionCallingConfig").path("mode").asString());

		JsonNode noneChoice = objectMapper.readTree("\"none\"");
		ObjectNode resNone = UniversalToolNormalizer.toGeminiToolConfig(noneChoice, objectMapper);
		assertNotNull(resNone);
		assertEquals("NONE", resNone.path("functionCallingConfig").path("mode").asString());

		JsonNode specificChoice = objectMapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\"calc\"}}");
		ObjectNode resSpec = UniversalToolNormalizer.toGeminiToolConfig(specificChoice, objectMapper);
		assertNotNull(resSpec);
		assertEquals("ANY", resSpec.path("functionCallingConfig").path("mode").asString());
		assertEquals("calc", resSpec.path("functionCallingConfig").path("allowedFunctionNames").get(0).asString());
	}

	@Test
	@DisplayName("generates unique synthetic tool call IDs")
	void generatesSyntheticToolCallIds() {
		String id1 = UniversalToolNormalizer.generateSyntheticToolCallId("get_weather", 0);
		String id2 = UniversalToolNormalizer.generateSyntheticToolCallId("get_weather", 0);
		String id3 = UniversalToolNormalizer.generateSyntheticToolCallId("get_weather", 1);

		assertTrue(id1.startsWith("call_gen_get_weat_0_"));
		assertTrue(id2.startsWith("call_gen_get_weat_0_"));
		assertTrue(id3.startsWith("call_gen_get_weat_1_"));
		assertNotEquals(id1, id2);
		assertNotEquals(id1, id3);
	}

	@Test
	@DisplayName("normalizes tool results for Gemini structured JSON responses")
	void normalizesToolResultsForGemini() {
		JsonNode jsonResult = UniversalToolNormalizer.normalizeToolResultForGemini(
				"{\"temp\": 72, \"condition\": \"sunny\"}",
				objectMapper
		);
		assertTrue(jsonResult.isObject());
		assertEquals(72, jsonResult.get("temp").asInt());

		JsonNode textResult = UniversalToolNormalizer.normalizeToolResultForGemini("Plain text output", objectMapper);
		assertTrue(textResult.isObject());
		assertEquals("Plain text output", textResult.get("result").asString());

		JsonNode nullResult = UniversalToolNormalizer.normalizeToolResultForGemini(null, objectMapper);
		assertTrue(nullResult.isObject());
	}

	@Test
	@DisplayName("handles empty or null tools and tool choices safely")
	void handlesNullsAndEmptySafely() {
		assertNull(UniversalToolNormalizer.toAnthropicTools(null, objectMapper));
		assertNull(UniversalToolNormalizer.toAnthropicTools(objectMapper.createArrayNode(), objectMapper));
		assertNull(UniversalToolNormalizer.toAnthropicTools(objectMapper.createObjectNode(), objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiTools(null, objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiTools(objectMapper.createArrayNode(), objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiTools(objectMapper.createObjectNode(), objectMapper));

		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(null, null, objectMapper));
		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(objectMapper.nullNode(), null, objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiToolConfig(null, objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiToolConfig(objectMapper.nullNode(), objectMapper));
	}

	@Test
	@DisplayName("tests all edge branches for Anthropic and Gemini tools translation")
	void testsEdgeBranchesForTools() throws Exception {
		// Non-object tools in array or invalid function nodes
		ArrayNode invalidTools = objectMapper.createArrayNode();
		invalidTools.add(123);
		invalidTools.addObject(); // empty object, no "function"
		invalidTools.addObject().putObject("function"); // empty function, no "name"
		invalidTools.addObject().putObject("function").put("name", "   "); // blank name

		assertNull(UniversalToolNormalizer.toAnthropicTools(invalidTools, objectMapper));
		assertNull(UniversalToolNormalizer.toGeminiTools(invalidTools, objectMapper));

		// Tool with no parameters and null description
		ArrayNode minimalTools = objectMapper.createArrayNode();
		ObjectNode tool = minimalTools.addObject();
		tool.put("type", "function");
		ObjectNode func = tool.putObject("function");
		func.put("name", "simple_func");
		func.putNull("description");

		ArrayNode anthropicRes = UniversalToolNormalizer.toAnthropicTools(minimalTools, objectMapper);
		assertNotNull(anthropicRes);
		assertEquals("simple_func", anthropicRes.get(0).get("name").asString());
		assertFalse(anthropicRes.get(0).has("description"));
		assertEquals("object", anthropicRes.get(0).get("input_schema").get("type").asString());

		ArrayNode geminiRes = UniversalToolNormalizer.toGeminiTools(minimalTools, objectMapper);
		assertNotNull(geminiRes);
		JsonNode fn = geminiRes.get(0).get("functionDeclarations").get(0);
		assertEquals("simple_func", fn.get("name").asString());
		assertFalse(fn.has("description"));
		assertFalse(fn.has("parameters"));
	}

	@Test
	@DisplayName("tests all edge branches for Anthropic tool choices")
	void testsEdgeBranchesForAnthropicToolChoice() throws Exception {
		JsonNode noneChoice = objectMapper.readTree("\"none\"");
		ObjectNode resNone = UniversalToolNormalizer.toAnthropicToolChoice(noneChoice, null, objectMapper);
		assertNotNull(resNone);
		assertEquals("none", resNone.get("type").asString());

		JsonNode customChoice = objectMapper.readTree("\"custom_mode\"");
		ObjectNode resCustom = UniversalToolNormalizer.toAnthropicToolChoice(customChoice, true, objectMapper);
		assertNotNull(resCustom);
		assertEquals("auto", resCustom.get("type").asString());
		assertFalse(resCustom.has("disable_parallel_tool_use"));

		JsonNode invalidObjChoice = objectMapper.readTree("{\"type\":\"custom\"}");
		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(invalidObjChoice, null, objectMapper));

		JsonNode emptyFuncChoice = objectMapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\" \"}}");
		assertNull(UniversalToolNormalizer.toAnthropicToolChoice(emptyFuncChoice, null, objectMapper));
	}

	@Test
	@DisplayName("tests all edge branches for Gemini parameters and types")
	void testsEdgeBranchesForGeminiParameters() throws Exception {
		assertEquals("OBJECT", UniversalToolNormalizer.mapToGeminiType(null));
		assertEquals("STRING", UniversalToolNormalizer.mapToGeminiType("string"));
		assertEquals("INTEGER", UniversalToolNormalizer.mapToGeminiType("integer"));
		assertEquals("NUMBER", UniversalToolNormalizer.mapToGeminiType("number"));
		assertEquals("BOOLEAN", UniversalToolNormalizer.mapToGeminiType("boolean"));
		assertEquals("ARRAY", UniversalToolNormalizer.mapToGeminiType("array"));
		assertEquals("OBJECT", UniversalToolNormalizer.mapToGeminiType("object"));
		assertEquals("OBJECT", UniversalToolNormalizer.mapToGeminiType("unknown_custom"));

		// Null or non-object schema
		ObjectNode nullParam = UniversalToolNormalizer.toGeminiParameters(null, objectMapper);
		assertEquals("OBJECT", nullParam.get("type").asString());

		ObjectNode nonObjParam = UniversalToolNormalizer.toGeminiParameters(
				objectMapper.createArrayNode(),
				objectMapper
		);
		assertEquals("OBJECT", nonObjParam.get("type").asString());

		// Schema with nested arrays, enums, non-string type, custom attributes
		String complexJson = """
				{
				  "type": 123,
				  "description": "Custom schema",
				  "properties": {
				    "status": {
				      "type": "string",
				      "enum": ["ACTIVE", "INACTIVE"]
				    },
				    "matrix": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "properties": {
				          "val": {"type": "integer"}
				        }
				      }
				    }
				  },
				  "required": ["status"],
				  "additionalCustom": "extra_val"
				}""";
		JsonNode complexSchema = objectMapper.readTree(complexJson);
		ObjectNode res = UniversalToolNormalizer.toGeminiParameters(complexSchema, objectMapper);
		assertNotNull(res);
		assertEquals("OBJECT", res.get("type").asString());
		assertEquals("Custom schema", res.get("description").asString());
		assertEquals("extra_val", res.get("additionalCustom").asString());
		assertTrue(res.get("required").isArray());
		assertEquals("STRING", res.path("properties").path("status").path("type").asString());
		assertTrue(res.path("properties").path("status").path("enum").isArray());
		assertEquals("ARRAY", res.path("properties").path("matrix").path("type").asString());
		assertEquals("OBJECT", res.path("properties").path("matrix").path("items").path("type").asString());
	}

	@Test
	@DisplayName("tests edge branches for Gemini tool choices")
	void testsEdgeBranchesForGeminiToolChoices() throws Exception {
		JsonNode customChoice = objectMapper.readTree("\"unknown_mode\"");
		ObjectNode res = UniversalToolNormalizer.toGeminiToolConfig(customChoice, objectMapper);
		assertNotNull(res);
		assertEquals("AUTO", res.path("functionCallingConfig").path("mode").asString());

		JsonNode nonFunctionObj = objectMapper.readTree("{\"type\":\"custom\"}");
		ObjectNode resNonFn = UniversalToolNormalizer.toGeminiToolConfig(nonFunctionObj, objectMapper);
		assertNotNull(resNonFn);
		assertFalse(resNonFn.path("functionCallingConfig").has("allowedFunctionNames"));

		JsonNode blankFnObj = objectMapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\" \"}}");
		ObjectNode resBlank = UniversalToolNormalizer.toGeminiToolConfig(blankFnObj, objectMapper);
		assertNotNull(resBlank);
		assertFalse(resBlank.path("functionCallingConfig").has("allowedFunctionNames"));

		// Function choice without function property
		JsonNode noFuncPropObj = objectMapper.readTree("{\"type\":\"function\"}");
		ObjectNode resNoFunc = UniversalToolNormalizer.toGeminiToolConfig(noFuncPropObj, objectMapper);
		assertNotNull(resNoFunc);
		assertFalse(resNoFunc.path("functionCallingConfig").has("allowedFunctionNames"));

		ObjectNode resAnthropicNoFunc = UniversalToolNormalizer.toAnthropicToolChoice(
				noFuncPropObj,
				null,
				objectMapper
		);
		assertNull(resAnthropicNoFunc);

		// Schema object without type property
		JsonNode schemaWithoutType = objectMapper.readTree("{\"description\": \"some schema without type\"}");
		ObjectNode geminiParams = UniversalToolNormalizer.toGeminiParameters(schemaWithoutType, objectMapper);
		assertEquals("OBJECT", geminiParams.get("type").asString());

		// Tools with non-object parameters
		ArrayNode toolsWithNonObjParams = objectMapper.createArrayNode();
		ObjectNode t = toolsWithNonObjParams.addObject();
		t.put("type", "function");
		ObjectNode fn = t.putObject("function");
		fn.put("name", "test_fn");
		fn.put("parameters", "not-an-object");

		ArrayNode anthropicTools = UniversalToolNormalizer.toAnthropicTools(toolsWithNonObjParams, objectMapper);
		assertNotNull(anthropicTools);
		assertEquals("object", anthropicTools.get(0).get("input_schema").get("type").asString());

		ArrayNode geminiTools = UniversalToolNormalizer.toGeminiTools(toolsWithNonObjParams, objectMapper);
		assertNotNull(geminiTools);
		assertFalse(geminiTools.get(0).get("functionDeclarations").get(0).has("parameters"));
	}

	@Test
	@DisplayName("tests synthetic ID and tool result normalization with null and empty inputs")
	void testsSyntheticIdAndResultNormalizer() {
		String idNullFunc = UniversalToolNormalizer.generateSyntheticToolCallId(null, 5);
		assertTrue(idNullFunc.startsWith("call_gen_5_"));

		String idShortFunc = UniversalToolNormalizer.generateSyntheticToolCallId("fn", 2);
		assertTrue(idShortFunc.startsWith("call_gen_fn_2_"));

		JsonNode emptyResult = UniversalToolNormalizer.normalizeToolResultForGemini("   ", objectMapper);
		assertEquals("", emptyResult.get("result").asString());

		JsonNode arrayResult = UniversalToolNormalizer.normalizeToolResultForGemini("[1, 2, 3]", objectMapper);
		assertEquals("[1, 2, 3]", arrayResult.get("result").asString());

		JsonNode invalidJsonResult = UniversalToolNormalizer.normalizeToolResultForGemini(
				"{invalid json",
				objectMapper
		);
		assertEquals("{invalid json", invalidJsonResult.get("result").asString());
	}
}
