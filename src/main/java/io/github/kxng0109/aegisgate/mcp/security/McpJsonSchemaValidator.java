package io.github.kxng0109.aegisgate.mcp.security;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * High-performance JSON Schema Draft 2020-12 parameter validator and dangerous pattern pre-filter for MCP tool
 * arguments.
 */
@Component
public class McpJsonSchemaValidator {

	// IEEE 754 safe integer limits
	public static final long MAX_SAFE_INTEGER = 9007199254740991L;
	public static final long MIN_SAFE_INTEGER = -9007199254740991L;

	private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("(?:\\.\\./|\\.\\.\\\\)");
	private static final Pattern DANGEROUS_CMD_SEPARATORS = Pattern.compile("[;&|`$]");

	/**
	 * Validates client-supplied tool arguments against the declared JSON Schema inputSchema.
	 *
	 * @param arguments incoming tool argument object
	 * @param schema    tool inputSchema from definition
	 * @return validation result
	 */
	public ValidationResult validate(@Nullable JsonNode arguments, @Nullable JsonNode schema) {
		if (schema == null || schema.isNull() || !schema.isObject()) {
			return ValidationResult.success();
		}

		// Arguments must be an object if schema expects an object
		if (arguments == null || arguments.isNull()) {
			if (schema.has("required") && !schema.path("required").isEmpty()) {
				return ValidationResult.error("Missing required arguments object");
			}
			return ValidationResult.success();
		}

		if (!arguments.isObject()) {
			return ValidationResult.error("Tool arguments must be a JSON object");
		}

		// 1. Required property enforcement
		if (schema.has("required") && schema.path("required").isArray()) {
			for (JsonNode reqField : schema.path("required")) {
				String fieldName = reqField.asText();
				if (!arguments.has(fieldName) || arguments.path(fieldName).isNull()) {
					return ValidationResult.error("Missing required parameter: '" + fieldName + "'");
				}
			}
		}

		JsonNode properties = schema.path("properties");
		boolean strictAdditionalProps = schema.has("additionalProperties")
				&& schema.path("additionalProperties").isBoolean()
				&& !schema.path("additionalProperties").asBoolean();

		Set<String> declaredProperties = new HashSet<>();
		if (properties.isObject()) {
			properties.properties().forEach(e -> declaredProperties.add(e.getKey()));
		}

		// 2. Validate declared properties and check for undeclared properties if additionalProperties: false
		for (var entry : arguments.properties()) {
			String propName = entry.getKey();
			JsonNode propVal = entry.getValue();

			if (strictAdditionalProps && !declaredProperties.contains(propName)) {
				return ValidationResult.error("Undeclared parameter not allowed: '" + propName + "'");
			}

			if (properties.has(propName)) {
				JsonNode propSchema = properties.path(propName);
				ValidationResult propRes = validateProperty(propName, propVal, propSchema);
				if (!propRes.isValid()) {
					return propRes;
				}
			}
		}

		return ValidationResult.success();
	}

	private ValidationResult validateProperty(String propName, JsonNode val, JsonNode schema) {
		if (val.isNull()) {
			return ValidationResult.success();
		}

		String expectedType = schema.path("type").asText("");

		// Type validation
		if (!expectedType.isBlank()) {
			switch (expectedType) {
				case "string" -> {
					if (!val.isTextual()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a string");
					}
					String text = val.asText();
					if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' length is below minLength");
					}
					if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' length exceeds maxLength");
					}
					if (schema.has("pattern")) {
						String regex = schema.path("pattern").asText();
						if (!Pattern.compile(regex).matcher(text).find()) {
							return ValidationResult.error(
									"Parameter '" + propName + "' does not match required pattern");
						}
					}
					// Pre-filter dangerous patterns on sensitive parameters
					if (isPathParameter(propName) && PATH_TRAVERSAL_PATTERN.matcher(text).find()) {
						return ValidationResult.error("Path traversal detected in parameter '" + propName + "'");
					}
				}
				case "integer" -> {
					if (!val.isIntegralNumber()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an integer");
					}
					long num = val.asLong();
					if (num < MIN_SAFE_INTEGER || num > MAX_SAFE_INTEGER) {
						return ValidationResult.error(
								"Parameter '" + propName + "' exceeds IEEE 754 safe integer range");
					}
					if (schema.has("minimum") && num < schema.path("minimum").asLong()) {
						return ValidationResult.error("Parameter '" + propName + "' is below minimum value");
					}
					if (schema.has("maximum") && num > schema.path("maximum").asLong()) {
						return ValidationResult.error("Parameter '" + propName + "' exceeds maximum value");
					}
				}
				case "number" -> {
					if (!val.isNumber()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a number");
					}
					double d = val.asDouble();
					if (schema.has("minimum") && d < schema.path("minimum").asDouble()) {
						return ValidationResult.error("Parameter '" + propName + "' is below minimum value");
					}
					if (schema.has("maximum") && d > schema.path("maximum").asDouble()) {
						return ValidationResult.error("Parameter '" + propName + "' exceeds maximum value");
					}
				}
				case "boolean" -> {
					if (!val.isBoolean()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a boolean");
					}
				}
				case "array" -> {
					if (!val.isArray()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an array");
					}
					if (schema.has("minItems") && val.size() < schema.path("minItems").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' item count is below minItems");
					}
					if (schema.has("maxItems") && val.size() > schema.path("maxItems").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' item count exceeds maxItems");
					}
				}
				case "object" -> {
					if (!val.isObject()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an object");
					}
				}
			}
		}

		return ValidationResult.success();
	}

	private boolean isPathParameter(String name) {
		String lower = name.toLowerCase();
		return lower.contains("path") || lower.contains("file") || lower.contains("dir") || lower.contains("uri");
	}

	public record ValidationResult(boolean isValid, @Nullable String errorMessage) {
		public static ValidationResult success() {
			return new ValidationResult(true, null);
		}

		public static ValidationResult error(String message) {
			return new ValidationResult(false, message);
		}
	}
}
