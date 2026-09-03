package io.github.kxng0109.aegisgate.security.guardrail.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@DisplayName("MidStreamKillSwitch Tests")
class MidStreamKillSwitchTest {

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<MidStreamKillSwitch> constructor = MidStreamKillSwitch.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		MidStreamKillSwitch instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("terminate writes SSE error event downstream and closes upstream stream")
	void terminateEmitsEventAndClosesUpstream() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AtomicBoolean upstreamClosed = new AtomicBoolean(false);
		AutoCloseable upstream = () -> upstreamClosed.set(true);

		MidStreamKillSwitch.terminate(out, upstream, "system_prompt_exfiltration");

		String output = out.toString(StandardCharsets.UTF_8);
		assertThat(output)
				.contains("event: error")
				.contains("\"type\":\"guardrail_violation\"")
				.contains("\"code\":\"content_filter\"")
				.contains("Stream terminated by AegisGate guardrail");

		assertThat(upstreamClosed.get()).isTrue();
	}

	@Test
	@DisplayName("terminate handles null output stream and null upstream stream gracefully")
	void terminateWithNullStreams() {
		// Should execute cleanly without throwing NPE
		MidStreamKillSwitch.terminate(null, null, "null_test");
	}

	@Test
	@DisplayName("terminate catches and logs downstream IOException during write")
	void catchesDownstreamIOException() throws Exception {
		OutputStream throwingOut = mock(OutputStream.class);
		doThrow(new IOException("Connection reset by peer")).when(throwingOut).write(any(byte[].class));

		AtomicBoolean upstreamClosed = new AtomicBoolean(false);
		AutoCloseable upstream = () -> upstreamClosed.set(true);

		MidStreamKillSwitch.terminate(throwingOut, upstream, "client_abort");
		assertThat(upstreamClosed.get()).isTrue();
	}

	@Test
	@DisplayName("terminate catches and logs upstream Exception during close")
	void catchesUpstreamException() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AutoCloseable throwingUpstream = () -> {
			throw new RuntimeException("Upstream socket close error");
		};

		// Does not bubble exception
		MidStreamKillSwitch.terminate(out, throwingUpstream, "upstream_close_error");
		assertThat(out.size()).isGreaterThan(0);
	}
}
