package io.github.kxng0109.aegisgate.security.guardrail.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamingJsonPdaValidator Tests")
class StreamingJsonPdaValidatorTest {

	@Test
	@DisplayName("validates streaming valid JSON object across multiple chunk deltas")
	void validatesValidStreamingJsonObject() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();

		assertThat(validator.ingest(null)).isTrue();
		assertThat(validator.ingest("")).isTrue();

		assertThat(validator.ingest("{\n  \"name\": \"Alice\",\n")).isTrue();
		assertThat(validator.ingest("  \"age\": 30,\n")).isTrue();
		assertThat(validator.ingest("  \"active\": true,\n")).isTrue();
		assertThat(validator.ingest("  \"roles\": [\"admin\", \"user\"],\n")).isTrue();
		assertThat(validator.ingest("  \"extra\": null\n}")).isTrue();

		assertThat(validator.isFinished()).isTrue();
		assertThat(validator.isRejected()).isFalse();
	}

	@Test
	@DisplayName("handles escaped quotes and characters inside string values")
	void handlesEscapedCharactersInStrings() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();

		assertThat(validator.ingest("{\"quote\": \"He said \\\"Hello\\\" and left.\", \"path\": \"C:\\\\dir\"}")).isTrue();
		assertThat(validator.isFinished()).isTrue();
		assertThat(validator.isRejected()).isFalse();
	}

	@Test
	@DisplayName("rejects colon outside of object key")
	void rejectsColonOutsideObjectKey() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();

		// Colon directly in array
		assertThat(validator.ingest("[:\"error\"]")).isFalse();
		assertThat(validator.isRejected()).isTrue();

		// Calling ingest when already rejected returns false immediately
		assertThat(validator.ingest("more")).isFalse();
	}

	@Test
	@DisplayName("rejects unexpected comma in object or array")
	void rejectsUnexpectedComma() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();
		assertThat(validator.ingest("{,}")).isFalse();
		assertThat(validator.isRejected()).isTrue();
	}

	@Test
	@DisplayName("rejects mismatched closing brackets and braces")
	void rejectsMismatchedClosures() {
		StreamingJsonPdaValidator validator1 = new StreamingJsonPdaValidator();
		// Mismatched brace in array
		assertThat(validator1.ingest("[1, 2, }")).isFalse();
		assertThat(validator1.isRejected()).isTrue();

		StreamingJsonPdaValidator validator2 = new StreamingJsonPdaValidator();
		// Mismatched bracket in object
		assertThat(validator2.ingest("{\"key\": 1]")).isFalse();
		assertThat(validator2.isRejected()).isTrue();
	}

	@Test
	@DisplayName("rejects invalid token characters in primitive values")
	void rejectsInvalidPrimitiveTokens() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();
		assertThat(validator.ingest("{\"key\": @invalid}")).isFalse();
		assertThat(validator.isRejected()).isTrue();
	}

	@Test
	@DisplayName("rejects deeply nested structures exceeding stack depth (MAX_DEPTH = 64)")
	void rejectsExceedingMaxDepth() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();

		// Nest 65 opening braces
		String deep = "{".repeat(65);
		assertThat(validator.ingest(deep)).isFalse();
		assertThat(validator.isRejected()).isTrue();

		// Nest 65 opening brackets for array depth limit
		StreamingJsonPdaValidator arrayValidator = new StreamingJsonPdaValidator();
		String deepArray = "[".repeat(65);
		assertThat(arrayValidator.ingest(deepArray)).isFalse();
		assertThat(arrayValidator.isRejected()).isTrue();
	}

	@Test
	@DisplayName("validates top-level array with numbers, negatives, decimals, tabs, and newlines")
	void validatesTopLevelArray() {
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();
		assertThat(validator.ingest("[\n\t1,\n\t-2.5,\n\t3\r\n]")).isTrue();
		assertThat(validator.isFinished()).isTrue();
		assertThat(validator.isRejected()).isFalse();
	}

	@Test
	@DisplayName("validates nested object closure and rejects colon after value")
	void validatesNestedObjectAndRejectsColonAfterValue() {
		// Nested object closing at depth > 0 (line 118)
		StreamingJsonPdaValidator validator = new StreamingJsonPdaValidator();
		assertThat(validator.ingest("{\"outer\": {\"inner\": 1}}")).isTrue();
		assertThat(validator.isFinished()).isTrue();

		// Colon outside of STATE_EXPECT_COLON (line 138)
		StreamingJsonPdaValidator badColonValidator = new StreamingJsonPdaValidator();
		assertThat(badColonValidator.ingest("{\"k\": \"v\": \"bad\"}")).isFalse();
		assertThat(badColonValidator.isRejected()).isTrue();
	}

	@Test
	@DisplayName("isFinished returns false when unstarted, mid-stream, or within unclosed string")
	void isFinishedPartialStates() {
		StreamingJsonPdaValidator fresh = new StreamingJsonPdaValidator();
		assertThat(fresh.isFinished()).isFalse(); // !started

		StreamingJsonPdaValidator midStream = new StreamingJsonPdaValidator();
		midStream.ingest("{\"key\": ");
		assertThat(midStream.isFinished()).isFalse(); // depth > 0

		StreamingJsonPdaValidator inString = new StreamingJsonPdaValidator();
		inString.ingest("{\"key\": \"unclosed");
		assertThat(inString.isFinished()).isFalse(); // inString
	}
}
