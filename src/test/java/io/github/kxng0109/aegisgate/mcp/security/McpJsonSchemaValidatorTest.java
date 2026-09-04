package io.github.kxng0109.aegisgate.mcp.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP JSON Schema Draft 2020-12 Validator Unit Tests")
class McpJsonSchemaValidatorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpJsonSchemaValidator validator;

	@BeforeEach
	void setUp() {
		validator = new McpJsonSchemaValidator();
	}

	@Test
	@DisplayName("Validates required fields in schema")
	void validatesRequiredFields() throws Exception {
		String schemaJson = """
				{
				  "type": "object",
				  "required": ["query", "limit"],
				  "properties": {
				    "query": {"type": "string"},
				    "limit": {"type": "integer"}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		// Missing limit
		JsonNode invalidArgs = objectMapper.readTree("{\"query\": \"SELECT 1\"}");
		McpJsonSchemaValidator.ValidationResult res1 = validator.validate(invalidArgs, schema);
		assertThat(res1.isValid()).isFalse();
		assertThat(res1.errorMessage()).contains("Missing required parameter: 'limit'");

		// Missing arguments object entirely
		McpJsonSchemaValidator.ValidationResult res2 = validator.validate(null, schema);
		assertThat(res2.isValid()).isFalse();
		assertThat(res2.errorMessage()).contains("Missing required arguments");

		// Valid arguments
		JsonNode validArgs = objectMapper.readTree("{\"query\": \"SELECT 1\", \"limit\": 10}");
		McpJsonSchemaValidator.ValidationResult res3 = validator.validate(validArgs, schema);
		assertThat(res3.isValid()).isTrue();
	}

	@Test
	@DisplayName("Rejects non-object arguments when schema is an object")
	void rejectsNonObjectArguments() throws Exception {
		JsonNode schema = objectMapper.readTree("{\"type\": \"object\"}");
		JsonNode arrayArgs = objectMapper.readTree("[\"item1\"]");

		McpJsonSchemaValidator.ValidationResult res = validator.validate(arrayArgs, schema);
		assertThat(res.isValid()).isFalse();
		assertThat(res.errorMessage()).contains("Tool arguments must be a JSON object");
	}

	@Test
	@DisplayName("Enforces strict additionalProperties false")
	void enforcesAdditionalPropertiesFalse() throws Exception {
		String schemaJson = """
				{
				  "type": "object",
				  "additionalProperties": false,
				  "properties": {
				    "query": {"type": "string"}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		JsonNode argsWithExtra = objectMapper.readTree("{\"query\": \"SELECT 1\", \"injectedField\": \"evil\"}");
		McpJsonSchemaValidator.ValidationResult res = validator.validate(argsWithExtra, schema);
		assertThat(res.isValid()).isFalse();
		assertThat(res.errorMessage()).contains("Undeclared parameter not allowed: 'injectedField'");
	}

	@Test
	@DisplayName("Validates string length, pattern regex, and path traversal detection")
	void validatesStringConstraints() throws Exception {
		String schemaJson = """
				{
				  "type": "object",
				  "properties": {
				    "code": {"type": "string", "minLength": 3, "maxLength": 5, "pattern": "^[A-Z]+$"},
				    "filePath": {"type": "string"}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		// Below minLength
		assertThat(validator.validate(objectMapper.readTree("{\"code\": \"AB\"}"), schema).errorMessage())
				.contains("length is below minLength");

		// Exceeds maxLength
		assertThat(validator.validate(objectMapper.readTree("{\"code\": \"ABCDEF\"}"), schema).errorMessage())
				.contains("length exceeds maxLength");

		// Pattern mismatch
		assertThat(validator.validate(objectMapper.readTree("{\"code\": \"abc\"}"), schema).errorMessage())
				.contains("does not match required pattern");

		// Path traversal detection on path parameter
		assertThat(validator.validate(objectMapper.readTree("{\"filePath\": \"../../etc/passwd\"}"), schema)
		                    .errorMessage())
				.contains("Path traversal detected");
		assertThat(validator.validate(objectMapper.readTree("{\"filePath\": \"..\\\\..\\\\boot.ini\"}"), schema)
		                    .errorMessage())
				.contains("Path traversal detected");
	}

	@Test
	@DisplayName("Validates integer bounds and IEEE 754 safe integer limits")
	void validatesIntegerBounds() throws Exception {
		String schemaJson = """
				{
				  "type": "object",
				  "properties": {
				    "count": {"type": "integer", "minimum": 1, "maximum": 100}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		// Type mismatch (string instead of integer)
		assertThat(validator.validate(objectMapper.readTree("{\"count\": \"10\"}"), schema).errorMessage())
				.contains("must be an integer");

		// Below minimum
		assertThat(validator.validate(objectMapper.readTree("{\"count\": 0}"), schema).errorMessage())
				.contains("is below minimum value");

		// Exceeds maximum
		assertThat(validator.validate(objectMapper.readTree("{\"count\": 101}"), schema).errorMessage())
				.contains("exceeds maximum value");

		// Safe integer limits ($> 2^53 - 1$)
		ObjectNode overflowArgs = objectMapper.createObjectNode();
		overflowArgs.put("count", 9007199254740992L);
		assertThat(validator.validate(overflowArgs, schema).errorMessage())
				.contains("exceeds IEEE 754 safe integer range");
	}

	@Test
	@DisplayName("Validates number, boolean, array, and object types")
	void validatesOtherDataTypes() throws Exception {
		String schemaJson = """
				{
				  "type": "object",
				  "properties": {
				    "price": {"type": "number", "minimum": 0.5, "maximum": 99.5},
				    "active": {"type": "boolean"},
				    "tags": {"type": "array", "minItems": 1, "maxItems": 3},
				    "config": {"type": "object"}
				  }
				}
				""";
		JsonNode schema = objectMapper.readTree(schemaJson);

		// Number bounds
		assertThat(validator.validate(objectMapper.readTree("{\"price\": 0.1}"), schema).errorMessage())
				.contains("is below minimum value");
		assertThat(validator.validate(objectMapper.readTree("{\"price\": 100.0}"), schema).errorMessage())
				.contains("exceeds maximum value");
		assertThat(validator.validate(objectMapper.readTree("{\"price\": \"ten\"}"), schema).errorMessage())
				.contains("must be a number");

		// Boolean
		assertThat(validator.validate(objectMapper.readTree("{\"active\": \"true\"}"), schema).errorMessage())
				.contains("must be a boolean");

		// Array bounds
		assertThat(validator.validate(objectMapper.readTree("{\"tags\": []}"), schema).errorMessage())
				.contains("item count is below minItems");
		assertThat(validator.validate(objectMapper.readTree("{\"tags\": [1,2,3,4]}"), schema).errorMessage())
				.contains("item count exceeds maxItems");
		assertThat(validator.validate(objectMapper.readTree("{\"tags\": \"tag1\"}"), schema).errorMessage())
				.contains("must be an array");

		// Object
		assertThat(validator.validate(objectMapper.readTree("{\"config\": \"scalar\"}"), schema).errorMessage())
				.contains("must be an object");

		// All valid (including active=false)
		JsonNode valid = objectMapper.readTree(
				"{\"price\": 50.0, \"active\": false, \"tags\": [\"a\", \"b\"], \"config\": {\"k\": \"v\"}}");
		assertThat(validator.validate(valid, schema).isValid()).isTrue();

		// additionalProperties: true permits undeclared keys
		JsonNode schemaWithAddPropsTrue = objectMapper.readTree(
				"{\"type\": \"object\", \"additionalProperties\": true, \"properties\": {\"a\": {\"type\": \"string\"}}}");
		assertThat(validator.validate(objectMapper.readTree("{\"a\": \"val\", \"extra\": 123}"), schemaWithAddPropsTrue)
		                    .isValid()).isTrue();

		// non-array required field
		JsonNode nonArrayReqSchema = objectMapper.readTree("{\"type\": \"object\", \"required\": \"invalid\"}");
		assertThat(validator.validate(objectMapper.readTree("{\"a\": 1}"), nonArrayReqSchema).isValid()).isTrue();

		// non-object properties field
		JsonNode nonObjPropsSchema = objectMapper.readTree("{\"type\": \"object\", \"properties\": \"invalid\"}");
		assertThat(validator.validate(objectMapper.readTree("{\"a\": 1}"), nonObjPropsSchema).isValid()).isTrue();
	}

	@Test
	@DisplayName("Handles null and empty schema/arguments gracefully")
	void handlesNullAndEmptyEdgeCases() throws Exception {
		// Null schema
		assertThat(validator.validate(objectMapper.readTree("{\"a\": 1}"), null).isValid()).isTrue();

		// Non-object schema
		assertThat(validator.validate(objectMapper.readTree("{\"a\": 1}"), objectMapper.readTree("\"string-schema\""))
		                    .isValid()).isTrue();

		// Schema without required fields and null arguments
		JsonNode optionalSchema = objectMapper.readTree(
				"{\"type\": \"object\", \"properties\": {\"opt\": {\"type\": \"string\"}}}");
		assertThat(validator.validate(null, optionalSchema).isValid()).isTrue();

		// Null field value in object
		JsonNode nullFieldArgs = objectMapper.readTree("{\"opt\": null}");
		assertThat(validator.validate(nullFieldArgs, optionalSchema).isValid()).isTrue();

		// Schema with un-typed property
		JsonNode untypedSchema = objectMapper.readTree("{\"type\": \"object\", \"properties\": {\"any\": {}}}");
		assertThat(validator.validate(objectMapper.readTree("{\"any\": \"value\"}"), untypedSchema).isValid()).isTrue();
	}
}
