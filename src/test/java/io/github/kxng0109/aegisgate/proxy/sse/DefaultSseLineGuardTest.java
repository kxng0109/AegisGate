package io.github.kxng0109.aegisgate.proxy.sse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DefaultSseLineGuard}.
 */
@DisplayName("DefaultSseLineGuard")
class DefaultSseLineGuardTest {

	private static SseLineGuardProperties defaultProperties() {
		return new SseLineGuardProperties(
				true,
				100,
				10,
				SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(
						SseLineGuard.ProviderType.OPENAI, new SseLineGuard.ProviderConfig(50, 10, 200),
						SseLineGuard.ProviderType.ANTHROPIC, new SseLineGuard.ProviderConfig(200, 5, 500)
				),
				Duration.ofSeconds(30),
				Duration.ofSeconds(5)
		);
	}

	private static DefaultSseLineGuard newGuard(SseLineGuardProperties props, SseLineGuard.ProviderType type, String name) {
		return new DefaultSseLineGuard(
				props,
				new SimpleMeterRegistry(),
				new ObjectMapper(),
				type,
				name,
				UUID.randomUUID()
		);
	}

	@Test
	@DisplayName("constructor rejects null registry")
	void constructorRejectsNullRegistry() {
		assertThatThrownBy(() -> new DefaultSseLineGuard(
				defaultProperties(), null, new ObjectMapper(),
				SseLineGuard.ProviderType.OPENAI, "openai", UUID.randomUUID()
		))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("line within limit returns the line and is not rejected")
	void lineWithinLimit() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai-east");
		List<String> result = guard.checkLine("hello", SseLineGuard.ProviderType.OPENAI);
		assertThat(result).containsExactly("hello");
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("line over the limit returns an SSE error event and rejects")
	void lineOverLimit() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai-east");
		String longLine = "x".repeat(100);
		List<String> result = guard.checkLine(longLine, SseLineGuard.ProviderType.OPENAI);
		assertThat(result).hasSize(3);
		assertThat(result.get(0)).isEqualTo("event: error");
		assertThat(result.get(1)).startsWith("data: ");
		assertThat(result.get(2)).isEmpty();
		assertThat(guard.isRejected()).isTrue();
	}

	@Test
	@DisplayName("disabled guard passes through all lines")
	void disabledGuard() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				false,
				100,
				10,
				SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(),
				Duration.ofSeconds(30),
				Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai-east");
		List<String> result = guard.checkLine("x".repeat(1_000), SseLineGuard.ProviderType.OPENAI);
		assertThat(result).containsExactly("x".repeat(1_000));
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("REJECT_LINE_CONTINUE action drops the line without rejecting")
	void rejectLineContinueDrops() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true,
				10,
				10,
				SseLineGuard.Action.REJECT_LINE_CONTINUE,
				Map.of(),
				Duration.ofSeconds(30),
				Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai-east");
		List<String> result = guard.checkLine("x".repeat(100), SseLineGuard.ProviderType.OPENAI);
		assertThat(result).isEmpty();
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("REJECT_LINE_AND_CLOSE rejects and sets rejected flag")
	void rejectLineAndCloseRejects() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai-east");
		String longLine = "x".repeat(100);
		guard.checkLine(longLine, SseLineGuard.ProviderType.OPENAI);
		assertThat(guard.isRejected()).isTrue();
	}

	@Test
	@DisplayName("Anthropic provider uses its own per-provider limit")
	void anthropicProvider() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.ANTHROPIC, "anthropic-east");
		List<String> result = guard.checkLine("x".repeat(150), SseLineGuard.ProviderType.ANTHROPIC);
		assertThat(result).containsExactly("x".repeat(150));
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("Unknown provider uses global default limit")
	void unknownProvider() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.UNKNOWN, "unknown");
		List<String> result = guard.checkLine("x".repeat(80), SseLineGuard.ProviderType.UNKNOWN);
		assertThat(result).containsExactly("x".repeat(80));
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("line rate limit triggers after burst")
	void lineRateLimit() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true, 1024, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(
						SseLineGuard.ProviderType.OPENAI, new SseLineGuard.ProviderConfig(16384, 3, 1_048_576)
				),
				Duration.ofSeconds(30), Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		IntStream.range(0, 3).forEach(i -> guard.checkLine("line" + i, SseLineGuard.ProviderType.OPENAI));
		List<String> rejected = guard.checkLine("overflow", SseLineGuard.ProviderType.OPENAI);
		assertThat(rejected).hasSize(3);
		assertThat(rejected.getFirst()).isEqualTo("event: error");
	}

	@Test
	@DisplayName("byte rate limit triggers after exceeding bytes-per-second")
	void byteRateLimit() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true, 1024, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(
						SseLineGuard.ProviderType.OPENAI, new SseLineGuard.ProviderConfig(16384, 1000, 20)
				),
				Duration.ofSeconds(30), Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		guard.checkLine("first-line-here", SseLineGuard.ProviderType.OPENAI);
		List<String> rejected = guard.checkLine("second-line", SseLineGuard.ProviderType.OPENAI);
		assertThat(rejected).hasSize(3);
	}

	@Test
	@DisplayName("multibyte UTF-8 counted as bytes, not chars")
	void multibyteUtf8CountedAsBytes() {
		SseLineGuardProperties props = new SseLineGuardProperties(
				true, 10, 10, SseLineGuard.Action.REJECT_LINE_AND_CLOSE,
				Map.of(),
				Duration.ofSeconds(30), Duration.ofSeconds(5)
		);
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		String cjk = "中文测试"; // 4 chars, 12 bytes
		assertThat(cjk.length()).isEqualTo(4);
		assertThat(cjk.getBytes(StandardCharsets.UTF_8).length).isEqualTo(12);

		// Limit is 10 bytes. If it were counting chars, 4 <= 10 would pass.
		// Since it counts bytes, 12 > 10, so it must be rejected!
		List<String> rejected = guard.checkLine(cjk, SseLineGuard.ProviderType.OPENAI);
		assertThat(rejected).hasSize(3);
		assertThat(guard.isRejected()).isTrue();
	}

	@Test
	@DisplayName("onStreamComplete and onStreamAbort do not throw")
	void lifecycleDoesNotThrow() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		guard.onStreamComplete();
		guard.onStreamAbort("client_disconnect");
		assertThat(guard.isRejected()).isFalse();
	}

	@Test
	@DisplayName("config() returns a snapshot with the configured values")
	void configSnapshot() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		SseLineGuard.ConfigSnapshot snapshot = guard.config();
		assertThat(snapshot.globalDefaultBytes()).isEqualTo(100);
		assertThat(snapshot.safetyMarginPercent()).isEqualTo(10);
		assertThat(snapshot.action()).isEqualTo(SseLineGuard.Action.REJECT_LINE_AND_CLOSE);
		assertThat(snapshot.perProvider()).containsKey(SseLineGuard.ProviderType.OPENAI);
	}

	@Test
	@DisplayName("abortReason is recorded on abort")
	void abortReasonRecorded() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		guard.onStreamAbort("line_too_long");
		assertThat(guard.abortReason()).isEqualTo("line_too_long");
	}

	@Test
	@DisplayName("checkLine with null provider uses global default limit")
	void checkLineWithNullProvider() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		List<String> result = guard.checkLine("hello", null);
		assertThat(result).containsExactly("hello");
	}

	@Test
	@DisplayName("abortReason is null before any abort")
	void abortReasonNullByDefault() {
		SseLineGuardProperties props = defaultProperties();
		DefaultSseLineGuard guard = newGuard(props, SseLineGuard.ProviderType.OPENAI, "openai");
		assertThat(guard.abortReason()).isNull();
	}
}