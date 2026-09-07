package io.github.kxng0109.aegisgate.mcp.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MCP JSON-RPC contract compliance (2026-07-28)")
class McpJsonRpcContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Set<Integer> ALLOWED_CODES =
			Set.of(-32700, -32600, -32601, -32602, -32603, -32020, -32021, -32022);

	@Nested
	@DisplayName("F-02 error-code partition")
	class ErrorCodePartition {

		static Stream<Arguments> factories() {
			return Stream.of(
					Arguments.of("parseError", McpJsonRpcError.parseError("x")),
					Arguments.of("invalidRequest", McpJsonRpcError.invalidRequest("x")),
					Arguments.of("methodNotFound", McpJsonRpcError.methodNotFound("m")),
					Arguments.of("invalidParams", McpJsonRpcError.invalidParams("x")),
					Arguments.of("internalError", McpJsonRpcError.internalError("x")),
					Arguments.of("accessDenied", McpJsonRpcError.accessDenied("t")),
					Arguments.of("circuitBreakerTripped", McpJsonRpcError.circuitBreakerTripped("s")),
					Arguments.of("headerMismatch", McpJsonRpcError.headerMismatch("x")),
					Arguments.of(
							"unsupportedVersion",
							McpJsonRpcError.unsupportedVersion("1900-01-01", MAPPER)
					)
			);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("factories")
		@DisplayName("every factory emits a spec-allowed code")
		void factoryInAllowedSet(String name, McpJsonRpcError error) {
			assertThat(ALLOWED_CODES).contains(error.code());
		}

		@Test
		@DisplayName("deleted and reserved codes do not exist as constants")
		void deletedCodesDoNotExist() {
			assertThat(Arrays.stream(McpJsonRpcError.class.getDeclaredFields())
			                 .map(Field::getName).toList())
					.doesNotContain(
							"RESOURCE_NOT_FOUND", "SERVER_ERROR",
							"HITL_SUSPENDED", "CIRCUIT_BREAKER_TRIPPED", "ACCESS_DENIED"
					);
			assertThatThrownBy(() -> McpJsonRpcError.class.getDeclaredField("RESOURCE_NOT_FOUND"))
					.isInstanceOf(NoSuchFieldException.class);
		}

		@Test
		@DisplayName("RBAC denial and circuit-tripped surface as -32603 with preserved messages")
		void localErrorsAreInternalError() {
			assertThat(McpJsonRpcError.accessDenied("x").code()).isEqualTo(-32603);
			assertThat(McpJsonRpcError.circuitBreakerTripped("s").code()).isEqualTo(-32603);
			assertThat(McpJsonRpcError.accessDenied("prohibited by security policy").message())
					.contains("prohibited by security policy");
			assertThat(McpJsonRpcError.circuitBreakerTripped("db").message())
					.contains("circuit breaker open");
		}
	}

	@Nested
	@DisplayName("F-03 resultType matrix")
	class ResultTypeMatrix {

		private JsonNode id(long value) {
			return MAPPER.getNodeFactory().numberNode(value);
		}

		@Test
		@DisplayName("plain object gains complete")
		void plainObjectGainsComplete() {
			ObjectNode result = MAPPER.createObjectNode().put("k", "v");
			McpJsonRpcResponse resp = McpJsonRpcResponse.success(id(1), result);
			assertThat(resp.result().path("resultType").asString()).isEqualTo("complete");
		}

		@Test
		@DisplayName("explicit input_required is preserved")
		void explicitInputRequiredPreserved() {
			ObjectNode result = MAPPER.createObjectNode().put("resultType", "input_required");
			McpJsonRpcResponse resp = McpJsonRpcResponse.success(id(1), result);
			assertThat(resp.result().path("resultType").asString()).isEqualTo("input_required");
		}

		@Test
		@DisplayName("non-object results pass through untouched")
		void nonObjectPassesThrough() {
			ArrayNode arr = MAPPER.createArrayNode().add(1);
			assertThat(McpJsonRpcResponse.success(id(2), arr).result()).isSameAs(arr);
			JsonNode text = MAPPER.getNodeFactory().textNode("x");
			assertThat(McpJsonRpcResponse.success(id(3), text).result()).isSameAs(text);
		}

		@Test
		@DisplayName("empty object gains complete")
		void emptyObjectGainsComplete() {
			ObjectNode result = MAPPER.createObjectNode();
			McpJsonRpcResponse resp = McpJsonRpcResponse.success(id(4), result);
			assertThat(resp.result().path("resultType").asString()).isEqualTo("complete");
		}

		@Test
		@DisplayName("null resultType value is repaired to complete")
		void nullResultTypeRepaired() {
			ObjectNode result = MAPPER.createObjectNode().putNull("resultType");
			McpJsonRpcResponse.success(id(5), result);
			assertThat(result.path("resultType").asString()).isEqualTo("complete");
		}

		@ParameterizedTest
		@NullSource
		@DisplayName("null id on success throws")
		void nullIdThrows(JsonNode nullId) {
			assertThatThrownBy(() -> McpJsonRpcResponse.success(nullId, MAPPER.createObjectNode()))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("F-04 id omission")
	class IdOmission {

		@Test
		@DisplayName("error with null id omits the member entirely")
		void errorNullIdOmitsMember() {
			ObjectNode node = McpJsonRpcResponse
					.failure(null, McpJsonRpcError.parseError("bad")).toJsonNode(MAPPER);
			assertThat(node.has("id")).isFalse();
			assertThat(node.toString()).doesNotContain("\"id\"");
		}

		@Test
		@DisplayName("error with JsonNull id omits the member entirely")
		void errorJsonNullIdOmitsMember() {
			ObjectNode node = McpJsonRpcResponse
					.failure(MAPPER.nullNode(), McpJsonRpcError.parseError("bad")).toJsonNode(MAPPER);
			assertThat(node.has("id")).isFalse();
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1, -1})
		@DisplayName("error echoes numeric ids including zero")
		void errorEchoesNumericIds(int value) {
			JsonNode id = MAPPER.getNodeFactory().numberNode(value);
			ObjectNode node = McpJsonRpcResponse
					.failure(id, McpJsonRpcError.invalidParams("x")).toJsonNode(MAPPER);
			assertThat(node.has("id")).isTrue();
			assertThat(node.get("id")).isEqualTo(id);
		}

		@Test
		@DisplayName("success always carries id")
		void successAlwaysCarriesId() {
			ObjectNode node = McpJsonRpcResponse.success(
					MAPPER.getNodeFactory().numberNode(0), MAPPER.createObjectNode()).toJsonNode(MAPPER);
			assertThat(node.has("id")).isTrue();
		}
	}
}
